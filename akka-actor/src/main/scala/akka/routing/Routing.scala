/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import annotation.tailrec

import akka.AkkaException
import akka.dispatch.Future
import akka.actor._
import akka.dispatch.Futures
import akka.event.EventHandler
import akka.actor.UntypedChannel._

import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger }

/**
 * An {@link AkkaException} thrown when something goes wrong while routing a message
 */
class RoutingException(message: String) extends AkkaException(message)

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
 *
 */
trait RouterConnections {

  /**
   * A version that is useful to see if there is any change in the connections. If there is a change, a router is
   * able to update its internal datastructures.
   */
  def version: Long

  /**
   * Returns the number of connections.
   */
  def size: Int

  /**
   * Returns a tuple containing the version and Iterable of all connected ActorRefs this Router uses to send messages to.
   *
   * This iterator should be 'persistent'. So it can be handed out to other threads so that they are working on
   * a stable (immutable) view of some set of connections.
   */
  def versionedIterator: (Long, Iterable[ActorRef])

  /**
   * A callback that can be used to indicate that a connected actorRef was dead.
   * <p/>
   * Implementations should make sure that this method can be called without the actorRef being part of the
   * current set of connections. The most logical way to deal with this situation, is just to ignore it. One of the
   * reasons this can happen is that multiple thread could at the 'same' moment discover for the same ActorRef that
   * not working.
   *
   * It could be that even after a remove has been called for a specific ActorRef, that the ActorRef
   * is still being used. A good behaving Router will eventually discard this reference, but no guarantees are
   * made how long this takes place.
   *
   * @param ref the dead
   */
  def remove(deadRef: ActorRef): Unit
}

object Routing {

  sealed trait RoutingMessage

  case class Broadcast(message: Any) extends RoutingMessage

  /**
   * Creates a new started RoutedActorRef that uses routing to deliver a message to one of its connected actors.
   *
   * @param actorAddress the address of the ActorRef.
   * @param connections an Iterable pointing to all connected actor references.
   * @param routerType the type of routing that should be used.
   * @throws IllegalArgumentException if the number of connections is zero, or if it depends on the actual router implementation
   *                                  how many connections it can handle.
   */
  def actorOf(actorAddress: String, connections: Iterable[ActorRef], routerType: RouterType): ActorRef = {
    val ref = routerType match {
      case RouterType.Direct ⇒
        if (connections.size > 1)
          throw new IllegalArgumentException("A direct router can't have more than 1 connection")
        actorOf(actorAddress, connections, new DirectRouter())

      case RouterType.Random ⇒
        actorOf(actorAddress, connections, new RandomRouter())

      case RouterType.RoundRobin ⇒
        actorOf(actorAddress, connections, new RoundRobinRouter())

      case _ ⇒ throw new IllegalArgumentException("Unsupported routerType " + routerType)
    }

    ref.start()
  }

  def actorOf(actorAddress: String, connections: Iterable[ActorRef], router: Router): ActorRef = {
    if (connections.size == 0)
      throw new IllegalArgumentException("To create a routed actor ref, at least one connection is required")

    new RoutedActorRef(actorAddress, router, connections)
  }

  def actorOfWithRoundRobin(actorAddress: String, connections: Iterable[ActorRef]): ActorRef = {
    actorOf(actorAddress, connections, akka.routing.RouterType.RoundRobin)
  }
}

/**
 * A RoutedActorRef is an ActorRef that has a set of connected ActorRef and it uses a Router to send a message to
 * on (or more) of these actors.
 */
class RoutedActorRef(val address: String, val router: Router, val connectionIterator: Iterable[ActorRef]) extends UnsupportedActorRef {

  router.init(new RoutedActorRefConnections(connectionIterator))

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

  def start(): this.type = synchronized[this.type] {
    _status = ActorRefInternals.RUNNING
    this
  }

  def stop() {
    synchronized {
      if (_status == ActorRefInternals.RUNNING) {
        _status = ActorRefInternals.SHUTDOWN
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)

        // FIXME here we need to fire off Actor.cluster.remove(address) (which needs to be properly implemented first, see ticket)

        //inetSocketAddressToActorRefMap.get.values foreach (_.stop()) // shut down all remote connections
      }
    }
  }

  private class RoutedActorRefConnections() extends RouterConnections {

    private val state = new AtomicReference[State]()

    def this(connectionIterable: Iterable[ActorRef]) = {
      this()
      state.set(new State(Long.MinValue, connectionIterable))
    }

    def version: Long = state.get().version

    def size: Int = state.get().connections.size

    def versionedIterator = {
      val s = state.get
      (s.version, s.connections)
    }

    @tailrec
    final def remove(ref: ActorRef) = {
      val oldState = state.get()

      //remote the ref from the connections.
      var newList = oldState.connections.filter(currentActorRef ⇒ currentActorRef ne ref)

      if (newList.size != oldState.connections.size) {
        //one or more occurrences of the actorRef were removed, so we need to update the state.

        val newState = new State(oldState.version + 1, newList)
        //if we are not able to update the state, we just try again.
        if (!state.compareAndSet(oldState, newState)) remove(ref)
      }
    }

    case class State(val version: Long, val connections: Iterable[ActorRef])
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
      connections.versionedIterator._2.foreach(actor ⇒
        try {
          actor.!(message)(sender)
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
            actor.!(message)(sender)
          } catch {
            case e: Exception ⇒
              connections.remove(actor)
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
              connections.remove(actor)
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
 * A DirectRouter is FIXME
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

      val (version, connectionIterable) = connections.versionedIterator

      if (connectionIterable.size > 1)
        throw new RoutingException("A DirectRouter can't have more than 1 connected Actor, but found [%s]".format(connectionIterable.size))

      val newState = new DirectRouterState(connectionIterable.head, version)
      if (state.compareAndSet(currentState, newState)) {
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      } else {
        //we failed to update the state, lets try again... better luck next time.
        getState()
      }
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
  private val random = new java.util.Random(System.currentTimeMillis)

  def next: Option[ActorRef] = {
    val state = getState()
    if (state.array.isEmpty) {
      None
    } else {
      val index = random.nextInt(state.array.length)
      Some(state.array(index))
    }
  }

  @tailrec
  private def getState(): RandomRouterState = {
    val currentState = state.get()

    if (currentState != null && currentState.version == connections.version) {
      //we are lucky, since there has not been any change in the connections. So therefor we can use the existing state.
      currentState
    } else {
      //there has been a change in connections, or it was the first try, so we need to update the internal state
      val (version, connectionIterable) = connections.versionedIterator
      val newState = new RandomRouterState(connectionIterable.toArray[ActorRef], version)

      if (state.compareAndSet(currentState, newState)) {
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      } else {
        //we failed to update the state, lets try again... better luck next time.
        getState()
      }
    }
  }

  private case class RandomRouterState(val array: Array[ActorRef], val version: Long)
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
      val (version, connectionIterable) = connections.versionedIterator
      val newState = new RoundRobinState(connectionIterable.toArray[ActorRef], version)

      if (state.compareAndSet(currentState, newState)) {
        //we are lucky since we just updated the state, so we can send it back as the state to use
        newState
      } else {
        //we failed to update the state, lets try again... better luck next time.
        getState()
      }
    }
  }

  private case class RoundRobinState(val array: Array[ActorRef], val version: Long) {

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
    val responses = connections.versionedIterator._2.flatMap { actor ⇒
      try {
        Some(actor.?(message, timeout)(sender).asInstanceOf[Future[S]])
      } catch {
        case e: Exception ⇒
          connections.remove(actor)
          None
      }
    }

    if (responses.size == 0)
      throw new RoutingException("No connections can process the message [%s] sent to scatter-gather router" format (message))

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
