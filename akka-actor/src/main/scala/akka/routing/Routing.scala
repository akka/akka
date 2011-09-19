/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import akka.AkkaException
import akka.actor._
import akka.event.EventHandler
import akka.config.ConfigurationException
import akka.actor.UntypedChannel._
import akka.dispatch.{ Future, Futures }
import akka.util.ReflectiveAccess

import java.net.InetSocketAddress
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger }

import scala.annotation.tailrec

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
  def init(connections: FailureDetector)

  /**
   * Routes the message to one of the connections.
   *
   * @throws RoutingException if something goes wrong while routing the message
   */
  def route(message: Any)(implicit sender: Option[ActorRef])

  /**
   * Routes the message using a timeout to one of the connections and returns a Future to synchronize on the
   * completion of the processing of the message.
   *
   * @throws RoutingExceptionif something goes wrong while routing the message.
   */
  def route[T](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[T]
}

/**
 * An Iterable that also contains a version.
 */
trait VersionedIterable[A] {
  val version: Long

  def iterable: Iterable[A]

  def apply(): Iterable[A] = iterable
}

/**
 * An {@link AkkaException} thrown when something goes wrong while routing a message
 */
class RoutingException(message: String) extends AkkaException(message)

/**
 * Default "local" failure detector. This failure detector removes an actor from the
 * router if an exception occured in the router's thread (e.g. when trying to add
 * the message to the receiver's mailbox).
 */
class RemoveConnectionOnFirstFailureLocalFailureDetector extends FailureDetector {

  case class State(version: Long, iterable: Iterable[ActorRef]) extends VersionedIterable[ActorRef]

  private val state = new AtomicReference[State]

  def this(connectionIterable: Iterable[ActorRef]) = {
    this()
    state.set(State(Long.MinValue, connectionIterable))
  }

  def isAvailable(connection: InetSocketAddress): Boolean =
    state.get.iterable.find(c ⇒ connection == c).isDefined

  def recordSuccess(connection: InetSocketAddress, timestamp: Long) {}

  def recordFailure(connection: InetSocketAddress, timestamp: Long) {}

  def version: Long = state.get.version

  def size: Int = state.get.iterable.size

  def versionedIterable = state.get

  def stopAll() {
    state.get.iterable foreach (_.stop())
  }

  def failOver(from: InetSocketAddress, to: InetSocketAddress) {} // do nothing here

  @tailrec
  final def remove(ref: ActorRef) = {
    val oldState = state.get

    //remote the ref from the connections.
    var newList = oldState.iterable.filter(currentActorRef ⇒ currentActorRef ne ref)

    if (newList.size != oldState.iterable.size) {
      //one or more occurrences of the actorRef were removed, so we need to update the state.

      val newState = State(oldState.version + 1, newList)
      //if we are not able to update the state, we just try again.
      if (!state.compareAndSet(oldState, newState)) remove(ref)
    }
  }
}

/**
 * A Helper class to create actor references that use routing.
 */
object Routing {

  sealed trait RoutingMessage

  case class Broadcast(message: Any) extends RoutingMessage

  /**
   * FIXME: will very likely be moved to the ActorRef.
   */
  def actorOf(props: RoutedProps, address: String = newUuid().toString): ActorRef = {
    //TODO Implement support for configuring by deployment ID etc
    //TODO If address matches an already created actor (Ahead-of-time deployed) return that actor
    //TODO If address exists in config, it will override the specified Props (should we attempt to merge?)
    //TODO If the actor deployed uses a different config, then ignore or throw exception?

    val clusteringEnabled = ReflectiveAccess.ClusterModule.isEnabled
    val localOnly = props.localOnly

    if (clusteringEnabled && !props.localOnly)
      ReflectiveAccess.ClusterModule.newClusteredActorRef(props)
    else {
      if (props.connections.isEmpty) //FIXME Shouldn't this be checked when instance is created so that it works with linking instead of barfing?
        throw new IllegalArgumentException("A routed actorRef can't have an empty connection set")

      new RoutedActorRef(props, address)
    }
  }

  /**
   * Creates a new started RoutedActorRef that uses routing to deliver a message to one of its connected actors.
   *
   * @param actorAddress the address of the ActorRef.
   * @param connections an Iterable pointing to all connected actor references.
   * @param routerType the type of routing that should be used.
   * @throws IllegalArgumentException if the number of connections is zero, or if it depends on the actual router implementation
   *                                  how many connections it can handle.
   */
  @deprecated("Use 'Routing.actorOf(props: RoutedProps)' instead.", "2.0")
  def actorOf(actorAddress: String, connections: Iterable[ActorRef], routerType: RouterType): ActorRef = {
    val router = routerType match {
      case RouterType.Direct if connections.size > 1 ⇒
        throw new IllegalArgumentException("A direct router can't have more than 1 connection")

      case RouterType.Direct ⇒
        new DirectRouter

      case RouterType.Random ⇒
        new RandomRouter

      case RouterType.RoundRobin ⇒
        new RoundRobinRouter

      case r ⇒
        throw new IllegalArgumentException("Unsupported routerType " + r)
    }

    if (connections.size == 0)
      throw new IllegalArgumentException("To create a routed actor ref, at least one connection is required")

    new RoutedActorRef(
      new RoutedProps(
        () ⇒ router,
        RoutedProps.defaultFailureDetectorFactory,
        connections,
        RoutedProps.defaultTimeout, true),
      actorAddress)
  }
}

/**
 * An Abstract convenience implementation for building an ActorReference that uses a Router.
 */
abstract private[akka] class AbstractRoutedActorRef(val props: RoutedProps) extends UnsupportedActorRef {

  val router = props.routerFactory()

  override def postMessageToMailbox(message: Any, channel: UntypedChannel) = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    router.route(message)(sender)
  }

  override def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any, timeout: Timeout, channel: UntypedChannel): Future[Any] = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    router.route[Any](message, timeout)(sender)
  }
}

/**
 * A RoutedActorRef is an ActorRef that has a set of connected ActorRef and it uses a Router to send a message to
 * on (or more) of these actors.
 */
private[akka] class RoutedActorRef(val routedProps: RoutedProps, val address: String) extends AbstractRoutedActorRef(routedProps) {

  @volatile
  private var running: Boolean = true

  def isRunning: Boolean = running

  def isShutdown: Boolean = !running

  def stop() {
    synchronized {
      if (running) {
        running = false
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)
      }
    }
  }

  router.init(new RemoveConnectionOnFirstFailureLocalFailureDetector(routedProps.connections))
}

/**
 * An Abstract Router implementation that already provides the basic infrastructure so that a concrete
 * Router only needs to implement the next method.
 *
 * FIXME: this is also the location where message buffering should be done in case of failure.
 */
trait BasicRouter extends Router {

  @volatile
  protected var connections: FailureDetector = _

  def init(connections: FailureDetector) = {
    this.connections = connections
  }

  def route(message: Any)(implicit sender: Option[ActorRef]) = message match {
    case Routing.Broadcast(message) ⇒
      //it is a broadcast message, we are going to send to message to all connections.
      connections.versionedIterable.iterable.foreach(actor ⇒
        try {
          actor.!(message)(sender) // we use original sender, so this is essentially a 'forward'
        } catch {
          case e: Exception ⇒
            connections.remove(actor)
            throw e
        })
    case _ ⇒
      //it no broadcast message, we are going to select an actor from the connections and send the message to him.
      next match {
        case Some(actor) ⇒
          try {
            actor.!(message)(sender) // we use original sender, so this is essentially a 'forward'
          } catch {
            case e: Exception ⇒
              connections.remove(actor)
              throw e
          }
        case None ⇒
          throwNoConnectionsError
      }
  }

  def route[T](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[T] = message match {
    case Routing.Broadcast(message) ⇒
      throw new RoutingException("Broadcasting using '?'/'ask' is for the time being is not supported. Use ScatterGatherRouter.")
    case _ ⇒
      //it no broadcast message, we are going to select an actor from the connections and send the message to him.
      next match {
        case Some(actor) ⇒
          try {
            // FIXME is this not wrong? it will not pass on and use the original Future but create a new one. Should reuse 'channel: UntypedChannel' in the AbstractRoutedActorRef
            actor.?(message, timeout)(sender).asInstanceOf[Future[T]]
          } catch {
            case e: Exception ⇒
              connections.remove(actor)
              throw e
          }
        case None ⇒
          throwNoConnectionsError
      }
  }

  protected def next: Option[ActorRef]

  private def throwNoConnectionsError = {
    val error = new RoutingException("No replica connections for router")
    EventHandler.error(error, this, error.toString)
    throw error
  }
}

/**
 * A DirectRouter a Router that only has a single connected actorRef and forwards all request to that actorRef.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class DirectRouter extends BasicRouter {

  private val state = new AtomicReference[DirectRouterState]

  lazy val next: Option[ActorRef] = {
    val currentState = getState
    if (currentState.ref == null) None else Some(currentState.ref)
  }

  // FIXME rename all 'getState' methods to 'currentState', non-scala
  @tailrec
  private def getState: DirectRouterState = {
    val currentState = state.get

    if (currentState != null && connections.version == currentState.version) {
      //we are lucky since nothing has changed in the connections.
      currentState
    } else {
      //there has been a change in the connections, or this is the first time this method is called. So we are going to do some updating.

      val versionedIterable = connections.versionedIterable

      val connectionCount = versionedIterable.iterable.size
      if (connectionCount > 1)
        throw new RoutingException("A DirectRouter can't have more than 1 connected Actor, but found [%s]".format(connectionCount))

      val newState = new DirectRouterState(versionedIterable.iterable.head, versionedIterable.version)
      if (state.compareAndSet(currentState, newState))
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      else //we failed to update the state, lets try again... better luck next time.
        getState
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

  private val state = new AtomicReference[RandomRouterState]

  //FIXME: threadlocal random?
  private val random = new java.util.Random(System.nanoTime)

  def next: Option[ActorRef] = getState.array match {
    case a if a.isEmpty ⇒ None
    case a              ⇒ Some(a(random.nextInt(a.length)))
  }

  @tailrec
  private def getState: RandomRouterState = {
    val currentState = state.get

    if (currentState != null && currentState.version == connections.version) {
      //we are lucky, since there has not been any change in the connections. So therefor we can use the existing state.
      currentState
    } else {
      //there has been a change in connections, or it was the first try, so we need to update the internal state

      val versionedIterable = connections.versionedIterable
      val newState = new RandomRouterState(versionedIterable.iterable.toIndexedSeq, versionedIterable.version)
      if (state.compareAndSet(currentState, newState))
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      else //we failed to update the state, lets try again... better luck next time.
        getState
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

  def next: Option[ActorRef] = getState.next

  @tailrec
  private def getState: RoundRobinState = {
    val currentState = state.get

    if (currentState != null && currentState.version == connections.version) {
      //we are lucky, since there has not been any change in the connections. So therefor we can use the existing state.
      currentState
    } else {
      //there has been a change in connections, or it was the first try, so we need to update the internal state

      val versionedIterable = connections.versionedIterable
      val newState = new RoundRobinState(versionedIterable.iterable.toIndexedSeq[ActorRef], versionedIterable.version)
      if (state.compareAndSet(currentState, newState))
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      else //we failed to update the state, lets try again... better luck next time.
        getState
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
   * Aggregates the responses into a single Future
   * @param results Futures of the responses from connections
   */
  protected def gather[S, G >: S](results: Iterable[Future[S]]): Future[G]

  private def scatterGather[S, G >: S](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[G] = {
    val responses = connections.versionedIterable.iterable.flatMap { actor ⇒
      try {
        Some(actor.?(message, timeout)(sender).asInstanceOf[Future[S]])
      } catch {
        case e: Exception ⇒
          connections.remove(actor)
          None
      }
    }

    if (responses.isEmpty)
      throw new RoutingException("No connections can process the message [%s] sent to scatter-gather router" format (message))
    else gather(responses)
  }

  override def route[T](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[T] = message match {
    case Routing.Broadcast(message) ⇒ scatterGather(message, timeout)
    case message                    ⇒ super.route(message, timeout)(sender)
  }
}

/**
 * Simple router that broadcasts the message to all connections, and replies with the first response
 * Scatter-gather pattern will be applied only to the messages broadcasted using Future
 * (wrapped into {@link Routing.Broadcast} and sent with "?" method). For the messages sent in a fire-forget
 * mode, the router would behave as {@link RoundRobinRouter}
 */
class ScatterGatherFirstCompletedRouter extends RoundRobinRouter with ScatterGatherRouter {

  protected def gather[S, G >: S](results: Iterable[Future[S]]): Future[G] = Future.firstCompletedOf(results)
}
