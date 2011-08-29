/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import annotation.tailrec

import akka.AkkaException
import akka.actor._
import akka.event.EventHandler
import akka.actor.UntypedChannel._

import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger }
import akka.dispatch.{ Future, Futures }
import akka.util.ReflectiveAccess
import collection.JavaConversions.iterableAsScalaIterable

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
   * A RouterType that selects the connection based on the least amount of cpu usage
   */
  object LeastCPU extends RouterType

  /**
   * A RouterType that select the connection based on the least amount of ram used.
   *
   * FIXME: this is extremely vague currently since there are so many ways to define least amount of ram.
   */
  object LeastRAM extends RouterType

  /**
   * A RouterType that select the connection where the actor has the least amount of messages in its mailbox.
   */
  object LeastMessages extends RouterType

  /**
   * A user-defined custom RouterType.
   */
  object Custom extends RouterType

}

object RoutedProps {

  final val defaultTimeout = Actor.TIMEOUT
  final val defaultRouterFactory = () ⇒ new RoundRobinRouter
  final val defaultDeployId = ""
  final val defaultLocalOnly = !ReflectiveAccess.ClusterModule.isEnabled

  /**
   * The default RoutedProps instance, uses the settings from the RoutedProps object starting with default*
   */
  final val default = new RoutedProps()

  def apply(): RoutedProps = default
}

/**
 * Contains the configuration to create local and clustered routed actor references.
 *
 * Routed ActorRef configuration object, this is thread safe and fully sharable.
 *
 * Because the Routers are stateful, a new Router instance needs to be created for every ActorRef that relies on routing
 * (currently the ClusterActorRef and the RoutedActorRef). That is why a Router factory is used (a function that returns
 * a new Router instance) instead of a single Router instance. This makes sharing the same RoutedProps between multiple
 * threads safe.
 *
 * This configuration object makes it possible to either
 */
case class RoutedProps(routerFactory: () ⇒ Router, deployId: String, connections: Iterable[ActorRef], timeout: Timeout, localOnly: Boolean) {

  def this() = this(
    routerFactory = RoutedProps.defaultRouterFactory,
    deployId = RoutedProps.defaultDeployId,
    connections = List(),
    timeout = RoutedProps.defaultTimeout,
    localOnly = RoutedProps.defaultLocalOnly)

  /**
   * Returns a new RoutedProps with the specified deployId set
   *
   *  Java and Scala API
   */
  def withDeployId(id: String): RoutedProps = copy(deployId = if (id eq null) "" else id)

  /**
   * Returns a new RoutedProps configured with a random router.
   *
   * Java and Scala API.
   */
  def withRandomRouter(): RoutedProps = copy(routerFactory = () ⇒ new RandomRouter())

  /**
   * Returns a new RoutedProps configured with a round robin router.
   *
   * Java and Scala API.
   */
  def withRoundRobinRouter(): RoutedProps = copy(routerFactory = () ⇒ new RoundRobinRouter())

  /**
   * Returns a new RoutedProps configured with a direct router.
   *
   * Java and Scala API.
   */
  def withDirectRouter(): RoutedProps = copy(routerFactory = () ⇒ new DirectRouter())

  /**
   * Makes it possible to change the default behavior in a clustered environment that a clustered actor ref is created.
   * In some cases you just want to have local actor references, even though the Cluster Module is up and running.
   *
   * Java and Scala API.
   */
  def withLocalOnly(l: Boolean = true) = copy(localOnly = l)

  /**
   * Sets the Router factory method to use. Since Router instance contain state, and should be linked to a single 'routed' ActorRef, a new
   * Router instance is needed for every 'routed' ActorRef. That is why a 'factory' function is used to create new
   * instances.
   *
   * Scala API.
   */
  def withRouter(f: () ⇒ Router): RoutedProps = copy(routerFactory = f)

  /**
   * Sets the RouterFactory to use. Since Router instance contain state, and should be linked to a single 'routed' ActorRef, a new
   * Router instance is needed for every 'routed' ActorRef. That is why a RouterFactory interface is used to create new
   * instances.
   *
   * Java API.
   */
  def withRouter(f: RouterFactory): RoutedProps = copy(routerFactory = () ⇒ f.newRouter())

  /**
   *
   */
  def withTimeout(t: Timeout): RoutedProps = copy(timeout = t)

  /**
   * Sets the connections to use.
   *
   * Scala API.
   */
  def withConnections(c: Iterable[ActorRef]): RoutedProps = copy(connections = c)

  /**
   * Sets the connections to use.
   *
   * Java API.
   */
  def withConnections(c: java.lang.Iterable[ActorRef]): RoutedProps = copy(connections = iterableAsScalaIterable(c))
}

/**
 * The Router is responsible for sending a message to one (or more) of its connections. Connections are stored in the
 * {@link RouterConnections} and each Router should be linked to only one {@link RouterConnections}.
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
  def init(connections: RouterConnections): Unit

  /**
   * Routes the message to one of the connections.
   *
   * @throws RoutingException if something goes wrong while routing the message
   */
  def route(message: Any)(implicit sender: Option[ActorRef]): Unit

  /**
   * Routes the message using a timeout to one of the connections and returns a Future to synchronize on the
   * completion of the processing of the message.
   *
   * @throws RoutingExceptionif something goes wrong while routing the message.
   */
  def route[T](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[T]
}

/**
 * An {@link AkkaException} thrown when something goes wrong while routing a message
 */
class RoutingException(message: String) extends AkkaException(message)

/**
 * The RouterConnection acts like a middleman between the Router and the actor reference that does the routing.
 * Through the RouterConnection:
 * <ol>
 *   <li>
 *     the actor ref can signal that something has changed in the known set of connections. The Router can see
 *     when a changed happened (by checking the version) and update its internal datastructures.
 *   </li>
 *   <li>
 *      the Router can indicate that some happened happened with a actor ref, e.g. the actor ref dying.
 *   </li>
 * </ol>
 *
 * It is very likely that the implementation of the RouterConnection will be part of the ActorRef itself.
 */
trait RouterConnections {

  /**
   * A version that is useful to see if there is any change in the connections. If there is a change, a router is
   * able to update its internal datastructures.
   */
  def version: Long

  /**
   * Returns the number of connections. Value could be stale as soon as received, and this method can't be combined (easily)
   * with an atomic read of and size and version.
   */
  def size: Int

  /**
   * Returns a VersionedIterator containing all connectected ActorRefs at some moment in time. Since there is
   * the time element, also the version is included to be able to read the data (the connections) and the version
   * in an atomic manner.
   *
   * This Iterable is 'persistent'. So it can be handed out to different threads and they see a stable (immutable)
   * view of some set of connections.
   */
  def versionedIterable: VersionedIterable[ActorRef]

  /**
   * A callback that can be used to indicate that a connected actorRef was dead.
   * <p/>
   * Implementations should make sure that this method can be called without the actorRef being part of the
   * current set of connections. The most logical way to deal with this situation, is just to ignore it. One of the
   * reasons this can happen is that multiple thread could at the 'same' moment discover for the same ActorRef that
   * not working.
   *
   * It could be that even after a signalDeadActor has been called for a specific ActorRef, that the ActorRef
   * is still being used. A good behaving Router will eventually discard this reference, but no guarantees are
   * made how long this takes.
   *
   * @param ref the dead
   */
  def signalDeadActor(deadRef: ActorRef): Unit
}

/**
 * An Iterable that also contains a version.
 */
case class VersionedIterable[A](version: Long, val iterable: Iterable[A])

/**
 * A Helper class to create actor references that use routing.
 */
object Routing {

  sealed trait RoutingMessage

  case class Broadcast(message: Any) extends RoutingMessage

  /**
   * todo: will very likely be moved to the ActorRef.
   */
  def actorOf(props: RoutedProps): ActorRef = {
    //TODO Implement support for configuring by deployment ID etc
    //TODO If deployId matches an already created actor (Ahead-of-time deployed) return that actor
    //TODO If deployId exists in config, it will override the specified Props (should we attempt to merge?)
    //TODO If the actor deployed uses a different config, then ignore or throw exception?

    val clusteringEnabled = ReflectiveAccess.ClusterModule.isEnabled
    val localOnly = props.localOnly

    if (!localOnly && !clusteringEnabled)
      throw new IllegalArgumentException("Can't have clustered actor reference without the ClusterModule being enabled")
    else if (clusteringEnabled && !props.localOnly) {
      ReflectiveAccess.ClusterModule.newClusteredActorRef(props).start()
    } else {
      if (props.connections.isEmpty)
        throw new IllegalArgumentException("A routed actorRef can't have an empty connection set")

      new RoutedActorRef(props).start()
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
  @deprecated
  def actorOf(actorAddress: String, connections: Iterable[ActorRef], routerType: RouterType): ActorRef = {
    val router = routerType match {
      case RouterType.Direct if connections.size > 1 ⇒
        throw new IllegalArgumentException("A direct router can't have more than 1 connection")
      case RouterType.Direct ⇒
        new DirectRouter()
      case RouterType.Random ⇒
        new RandomRouter()
      case RouterType.RoundRobin ⇒
        new RoundRobinRouter()
      case r ⇒
        throw new IllegalArgumentException("Unsupported routerType " + r)
    }

    if (connections.size == 0)
      throw new IllegalArgumentException("To create a routed actor ref, at least one connection is required")

    val props = new RoutedProps(() ⇒ router, actorAddress, connections, RoutedProps.defaultTimeout, true)
    new RoutedActorRef(props).start()
  }
}

/**
 * An Abstract convenience implementation for building an ActorReference that uses a Router.
 */
abstract private[akka] class AbstractRoutedActorRef(val props: RoutedProps) extends UnsupportedActorRef {

  val router = props.routerFactory.apply()

  def address = props.deployId

  override def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = {
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
private[akka] class RoutedActorRef(val routedProps: RoutedProps)
  extends AbstractRoutedActorRef(routedProps) {

  router.init(new RoutedActorRefConnections(routedProps.connections))

  def start(): this.type = synchronized[this.type] {
    if (_status == ActorRefInternals.UNSTARTED)
      _status = ActorRefInternals.RUNNING
    this
  }

  def stop() {
    synchronized {
      if (_status == ActorRefInternals.RUNNING) {
        _status = ActorRefInternals.SHUTDOWN
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)
      }
    }
  }

  private class RoutedActorRefConnections() extends RouterConnections {

    private val state = new AtomicReference[VersionedIterable[ActorRef]]()

    def this(connectionIterable: Iterable[ActorRef]) = {
      this()
      state.set(new VersionedIterable[ActorRef](Long.MinValue, connectionIterable))
    }

    def version: Long = state.get().version

    def size: Int = state.get().iterable.size

    def versionedIterable = state.get

    @tailrec
    final def signalDeadActor(ref: ActorRef) = {
      val oldState = state.get()

      //remote the ref from the connections.
      var newList = oldState.iterable.filter(currentActorRef ⇒ currentActorRef ne ref)

      if (newList.size != oldState.iterable.size) {
        //one or more occurrences of the actorRef were removed, so we need to update the state.

        val newState = new VersionedIterable[ActorRef](oldState.version + 1, newList)
        //if we are not able to update the state, we just try again.
        if (!state.compareAndSet(oldState, newState)) signalDeadActor(ref)
      }
    }
  }
}

/**
 * An Abstract Router implementation that already provides the basic infrastructure so that a concrete
 * Router only needs to implement the next method.
 *
 * FIXME: This also is the location where a failover  is done in the future if an ActorRef fails and a different one needs to be selected.
 * FIXME: this is also the location where message buffering should be done in case of failure.
 */
trait BasicRouter extends Router {

  @volatile
  protected var connections: RouterConnections = _

  def init(connections: RouterConnections) = {
    this.connections = connections
  }

  def route(message: Any)(implicit sender: Option[ActorRef]): Unit = message match {
    case Routing.Broadcast(message) ⇒
      //it is a broadcast message, we are going to send to message to all connections.
      connections.versionedIterable.iterable.foreach(actor ⇒
        try {
          actor.!(message)(sender)
        } catch {
          case e: Exception ⇒
            connections.signalDeadActor(actor)
            throw e
        })
    case _ ⇒
      //it no broadcast message, we are going to select an actor from the connections and send the message to him.
      next match {
        case Some(actor) ⇒
          try {
            actor.!(message)(sender)
          } catch {
            case e: Exception ⇒
              connections.signalDeadActor(actor)
              throw e
          }
        case None ⇒
          throwNoConnectionsError()
      }
  }

  def route[T](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[T] = message match {
    case Routing.Broadcast(message) ⇒
      throw new RoutingException("Broadcasting using '?' is for the time being is not supported. Use ScatterGatherRouter.")
    case _ ⇒
      //it no broadcast message, we are going to select an actor from the connections and send the message to him.
      next match {
        case Some(actor) ⇒
          try {
            actor.?(message, timeout)(sender).asInstanceOf[Future[T]]
          } catch {
            case e: Exception ⇒
              connections.signalDeadActor(actor)
              throw e
          }
        case None ⇒
          throwNoConnectionsError()
      }
  }

  protected def next: Option[ActorRef]

  private def throwNoConnectionsError() = {
    val error = new RoutingException("No replica connections for router")
    EventHandler.error(error, this, error.toString)
    throw error
  }
}

/**
 * A DirectRouter a Router that only has a single connected actorRef and forwards all request to that actorRef.
 *
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class DirectRouter extends BasicRouter {

  private val state = new AtomicReference[DirectRouterState]()

  lazy val next: Option[ActorRef] = {
    val currentState = getState()
    if (currentState.ref == null) None else Some(currentState.ref)
  }

  @tailrec
  private def getState(): DirectRouterState = {
    val currentState = state.get()

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
        getState()
    }
  }

  private case class DirectRouterState(val ref: ActorRef, val version: Long)

}

/**
 * A Router that randomly selects one of the target connections to send a message to.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RandomRouter extends BasicRouter {

  private val state = new AtomicReference[RandomRouterState]()

  //FIXME: threadlocal random?
  private val random = new java.util.Random(System.nanoTime())

  def next: Option[ActorRef] = getState().array match {
    case a if a.isEmpty ⇒ None
    case a              ⇒ Some(a(random.nextInt(a.length)))
  }

  @tailrec
  private def getState(): RandomRouterState = {
    val currentState = state.get()

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
        getState()
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

  private val state = new AtomicReference[RoundRobinState]()

  def next: Option[ActorRef] = getState().next()

  @tailrec
  private def getState(): RoundRobinState = {
    val currentState = state.get()

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
        getState()
    }
  }

  private case class RoundRobinState(array: IndexedSeq[ActorRef], version: Long) {

    private val index = new AtomicInteger(0)

    def next(): Option[ActorRef] = if (array.isEmpty) None else Some(array(nextIndex()))

    @tailrec
    private def nextIndex(): Int = {
      val oldIndex = index.get()
      var newIndex = if (oldIndex == array.length - 1) 0 else oldIndex + 1

      if (!index.compareAndSet(oldIndex, newIndex)) nextIndex()
      else oldIndex
    }
  }
}

/*
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

  /*
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
          connections.signalDeadActor(actor)
          None
      }
    }

    if (responses.isEmpty)
      throw new RoutingException("No connections can process the message [%s] sent to scatter-gather router" format (message))
    else
      gather(responses)
  }

  override def route[T](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[T] = message match {
    case Routing.Broadcast(message) ⇒ scatterGather(message, timeout)
    case message                    ⇒ super.route(message, timeout)(sender)
  }

}

/*
 * Simple router that broadcasts the message to all connections, and replies with the first response
 * Scatter-gather pattern will be applied only to the messages broadcasted using Future 
 * (wrapped into {@link Routing.Broadcast} and sent with "?" method). For the messages sent in a fire-forget
 * mode, the router would behave as {@link RoundRobinRouter} 
 */
class ScatterGatherFirstCompletedRouter extends RoundRobinRouter with ScatterGatherRouter {

  protected def gather[S, G >: S](results: Iterable[Future[S]]): Future[G] = Futures.firstCompletedOf(results)

}
