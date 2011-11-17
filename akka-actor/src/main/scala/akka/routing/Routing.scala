/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.AkkaException
import akka.actor._
import akka.config.ConfigurationException
import akka.dispatch.{ Future, MessageDispatcher }
import akka.util.{ ReflectiveAccess, Duration }
import java.net.InetSocketAddress
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger }

import scala.annotation.tailrec

sealed trait RouterType

/**
 * Used for declarative configuration of Routing.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object RouterType {

  object Direct extends RouterType

  /**
   * A RouterType that randomly selects a connection to send a message to.
   */
  object Random extends RouterType

  /**
   * A RouterType that selects the connection by using round robin.
   */
  object RoundRobin extends RouterType

  /**
   * A RouterType that selects the connection by using scatter gather.
   */
  object ScatterGather extends RouterType

  /**
   * A RouterType that selects the connection based on the least amount of cpu usage
   */
  object LeastCPU extends RouterType

  /**
   * A RouterType that select the connection based on the least amount of ram used.
   */
  object LeastRAM extends RouterType

  /**
   * A RouterType that select the connection where the actor has the least amount of messages in its mailbox.
   */
  object LeastMessages extends RouterType

  /**
   * A user-defined custom RouterType.
   */
  case class Custom(implClass: String) extends RouterType
}

/**
 * Contains the configuration to create local and clustered routed actor references.
 * Routed ActorRef configuration object, this is thread safe and fully sharable.
 */
case class RoutedProps private[akka] (
  routerFactory: () ⇒ Router = RoutedProps.defaultRouterFactory,
  connectionManager: ConnectionManager = new LocalConnectionManager(List()),
  timeout: Timeout = RoutedProps.defaultTimeout,
  localOnly: Boolean = RoutedProps.defaultLocalOnly) {
}

object RoutedProps {
  final val defaultTimeout = Timeout(Duration.MinusInf)
  final val defaultRouterFactory = () ⇒ new RoundRobinRouter
  final val defaultLocalOnly = false
}

/**
 * The Router is responsible for sending a message to one (or more) of its connections. Connections are stored in the
 * {@link FailureDetector} and each Router should be linked to only one {@link FailureDetector}.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Router {

  /**
   * Initializes this Router with a given set of Connections. The Router can use this datastructure to ask for
   * the current connections, signal that there were problems with one of the connections and see if there have
   * been changes in the connections.
   *
   * This method is not threadsafe, and should only be called once
   *
   * JMM Guarantees:
   * This method guarantees that all changes made in this method, are visible before one of the routing methods is called.
   */
  def init(connectionManager: ConnectionManager)

  /**
   * Routes the message to one of the connections.
   *
   * @throws RoutingException if something goes wrong while routing the message
   */
  def route(message: Any)(implicit sender: ActorRef)

  /**
   * Routes the message using a timeout to one of the connections and returns a Future to synchronize on the
   * completion of the processing of the message.
   *
   * @throws RoutingExceptionif something goes wrong while routing the message.
   */
  def route[T](message: Any, timeout: Timeout): Future[T]
}

/**
 * An {@link AkkaException} thrown when something goes wrong while routing a message
 */
class RoutingException(message: String) extends AkkaException(message)

/**
 * A Helper class to create actor references that use routing.
 */
object Routing {

  sealed trait RoutingMessage

  /**
   * Used to broadcast a message to all connections in a router. E.g. every connection gets the message
   * regardless of their routing algorithm.
   */
  case class Broadcast(message: Any) extends RoutingMessage

  def createCustomRouter(implClass: String): Router = {
    ReflectiveAccess.createInstance(
      implClass,
      Array[Class[_]](),
      Array[AnyRef]()) match {
        case Right(router) ⇒ router.asInstanceOf[Router]
        case Left(exception) ⇒
          val cause = exception match {
            case i: InvocationTargetException ⇒ i.getTargetException
            case _                            ⇒ exception
          }
          throw new ConfigurationException(
            "Could not instantiate custom Router of [" +
              implClass + "] due to: " +
              cause, cause)
      }
  }
}

/**
 * An Abstract convenience implementation for building an ActorReference that uses a Router.
 */
abstract private[akka] class AbstractRoutedActorRef(val system: ActorSystem, val props: RoutedProps) extends MinimalActorRef {
  val router = props.routerFactory()

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = router.route(message)(sender)

  override def ?(message: Any)(implicit timeout: Timeout): Future[Any] = router.route(message, timeout)
}

/**
 * A RoutedActorRef is an ActorRef that has a set of connected ActorRef and it uses a Router to send a message to
 * on (or more) of these actors.
 */
private[akka] class RoutedActorRef(system: ActorSystem, val routedProps: RoutedProps, val supervisor: ActorRef, override val name: String) extends AbstractRoutedActorRef(system, routedProps) {

  val path = supervisor.path / name

  // FIXME (actor path): address normally has host and port, what about routed actor ref?
  def address = "routed:/" + path.toString

  @volatile
  private var running: Boolean = true

  override def isShutdown: Boolean = !running

  override def stop() {
    synchronized {
      if (running) {
        running = false
        router.route(Routing.Broadcast(PoisonPill))(this)
      }
    }
  }

  router.init(routedProps.connectionManager)
}

/**
 * An Abstract Router implementation that already provides the basic infrastructure so that a concrete
 * Router only needs to implement the next method.
 */
trait BasicRouter extends Router {

  @volatile
  protected var connectionManager: ConnectionManager = _

  def init(connectionManager: ConnectionManager) = {
    this.connectionManager = connectionManager
  }

  def route(message: Any)(implicit sender: ActorRef) = message match {
    case Routing.Broadcast(message) ⇒

      //it is a broadcast message, we are going to send to message to all connections.
      connectionManager.connections.iterable foreach { connection ⇒
        try {
          connection.!(message)(sender) // we use original sender, so this is essentially a 'forward'
        } catch {
          case e: Exception ⇒
            connectionManager.remove(connection)
            throw e
        }
      }
    case _ ⇒
      //it no broadcast message, we are going to select an actor from the connections and send the message to him.
      next match {
        case Some(connection) ⇒
          try {
            connection.!(message)(sender) // we use original sender, so this is essentially a 'forward'
          } catch {
            case e: Exception ⇒
              connectionManager.remove(connection)
              throw e
          }
        case None ⇒
          throwNoConnectionsError
      }
  }

  def route[T](message: Any, timeout: Timeout): Future[T] = message match {
    case Routing.Broadcast(message) ⇒
      throw new RoutingException("Broadcasting using '?'/'ask' is for the time being is not supported. Use ScatterGatherRouter.")
    case _ ⇒
      //it no broadcast message, we are going to select an actor from the connections and send the message to him.
      next match {
        case Some(connection) ⇒
          try {
            connection.?(message, timeout).asInstanceOf[Future[T]] //FIXME this does not preserve the original sender, shouldn't it?
          } catch {
            case e: Exception ⇒
              connectionManager.remove(connection)
              throw e
          }
        case None ⇒
          throwNoConnectionsError
      }
  }

  protected def next: Option[ActorRef]

  private def throwNoConnectionsError = throw new RoutingException("No replica connections for router")
}

/**
 * A DirectRouter a Router that only has a single connected actorRef and forwards all request to that actorRef.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class DirectRouter extends BasicRouter {

  private val state = new AtomicReference[DirectRouterState]

  lazy val next: Option[ActorRef] = {
    val current = currentState
    if (current.ref == null) None else Some(current.ref)
  }

  @tailrec
  private def currentState: DirectRouterState = {
    val current = state.get

    if (current != null && connectionManager.version == current.version) {
      //we are lucky since nothing has changed in the connections.
      current
    } else {
      //there has been a change in the connections, or this is the first time this method is called. So we are going to do some updating.

      val connections = connectionManager.connections

      val connectionCount = connections.iterable.size
      if (connectionCount > 1)
        throw new RoutingException("A DirectRouter can't have more than 1 connected Actor, but found [%s]".format(connectionCount))

      val newState = new DirectRouterState(connections.iterable.head, connections.version)
      if (state.compareAndSet(current, newState))
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      else //we failed to update the state, lets try again... better luck next time.
        currentState // recur
    }
  }

  private case class DirectRouterState(ref: ActorRef, version: Long)
}

/**
 * A Router that randomly selects one of the target connections to send a message to.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RandomRouter extends BasicRouter {
  import java.security.SecureRandom

  private val state = new AtomicReference[RandomRouterState]

  private val random = new ThreadLocal[SecureRandom] {
    override def initialValue = SecureRandom.getInstance("SHA1PRNG")
  }

  def next: Option[ActorRef] = currentState.array match {
    case a if a.isEmpty ⇒ None
    case a              ⇒ Some(a(random.get.nextInt(a.length)))
  }

  @tailrec
  private def currentState: RandomRouterState = {
    val current = state.get

    if (current != null && current.version == connectionManager.version) {
      //we are lucky, since there has not been any change in the connections. So therefor we can use the existing state.
      current
    } else {
      //there has been a change in connections, or it was the first try, so we need to update the internal state

      val connections = connectionManager.connections
      val newState = new RandomRouterState(connections.iterable.toIndexedSeq, connections.version)
      if (state.compareAndSet(current, newState))
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      else //we failed to update the state, lets try again... better luck next time.
        currentState
    }
  }

  private case class RandomRouterState(array: IndexedSeq[ActorRef], version: Long)
}

/**
 * A Router that uses round-robin to select a connection. For concurrent calls, round robin is just a best effort.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RoundRobinRouter extends BasicRouter {

  private val state = new AtomicReference[RoundRobinState]

  def next: Option[ActorRef] = currentState.next

  @tailrec
  private def currentState: RoundRobinState = {
    val current = state.get

    if (current != null && current.version == connectionManager.version) {
      //we are lucky, since there has not been any change in the connections. So therefor we can use the existing state.
      current
    } else {
      //there has been a change in connections, or it was the first try, so we need to update the internal state

      val connections = connectionManager.connections
      val newState = new RoundRobinState(connections.iterable.toIndexedSeq[ActorRef], connections.version)
      if (state.compareAndSet(current, newState))
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      else //we failed to update the state, lets try again... better luck next time.
        currentState
    }
  }

  private case class RoundRobinState(array: IndexedSeq[ActorRef], version: Long) {

    private val index = new AtomicInteger(0)

    def next: Option[ActorRef] = if (array.isEmpty) None else Some(array(nextIndex))

    @tailrec
    private def nextIndex: Int = {
      val oldIndex = index.get
      var newIndex = if (oldIndex == array.length - 1) 0 else oldIndex + 1

      if (!index.compareAndSet(oldIndex, newIndex)) nextIndex
      else oldIndex
    }
  }
}

/**
 * ScatterGatherRouter broadcasts the message to all connections and gathers results according to the
 * specified strategy (specific router needs to implement `gather` method).
 * Scatter-gather pattern will be applied only to the messages broadcasted using Future
 * (wrapped into {@link Routing.Broadcast} and sent with "?" method). For the messages, sent in a fire-forget
 * mode, the router would behave as {@link BasicRouter}, unless it's mixed in with other router type
 *
 *  FIXME: This also is the location where a failover  is done in the future if an ActorRef fails and a different one needs to be selected.
 * FIXME: this is also the location where message buffering should be done in case of failure.
 */
trait ScatterGatherRouter extends BasicRouter with Serializable {

  /**
   * Aggregates the responses into a single Future.
   *
   * @param results Futures of the responses from connections
   */
  protected def gather[S, G >: S](results: Iterable[Future[S]]): Future[G]

  private def scatterGather[S, G >: S](message: Any, timeout: Timeout): Future[G] = {
    val responses = connectionManager.connections.iterable.flatMap { actor ⇒
      try {
        if (actor.isShutdown) throw ActorInitializationException(actor, "For compatability - check death first", new Exception) // for stack trace
        Some(actor.?(message, timeout).asInstanceOf[Future[S]])
      } catch {
        case e: Exception ⇒
          connectionManager.remove(actor)
          None
      }
    }

    if (responses.isEmpty)
      throw new RoutingException("No connections can process the message [%s] sent to scatter-gather router" format (message))
    else gather(responses)
  }

  override def route[T](message: Any, timeout: Timeout): Future[T] = message match {
    case Routing.Broadcast(message) ⇒ scatterGather(message, timeout)
    case message                    ⇒ super.route(message, timeout)
  }
}

/**
 * Simple router that broadcasts the message to all connections, and replies with the first response
 * Scatter-gather pattern will be applied only to the messages broadcasted using Future
 * (wrapped into {@link Routing.Broadcast} and sent with "?" method). For the messages sent in a fire-forget
 * mode, the router would behave as {@link RoundRobinRouter}
 */
class ScatterGatherFirstCompletedRouter(implicit val dispatcher: MessageDispatcher, timeout: Timeout) extends RoundRobinRouter with ScatterGatherRouter {

  protected def gather[S, G >: S](results: Iterable[Future[S]]): Future[G] = Future.firstCompletedOf(results)
}
