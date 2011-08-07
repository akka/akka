/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing

import java.util.concurrent.atomic.AtomicReference
import annotation.tailrec

import akka.AkkaException
import akka.dispatch.Future
import java.net.InetSocketAddress
import akka.actor._
import akka.event.EventHandler
import akka.actor.UntypedChannel._
import akka.routing.RouterType.RoundRobin

class RoutingException(message: String) extends AkkaException(message)

sealed trait RouterType

/**
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
   * todo: this is extremely vague currently since there are so many ways to define least amount of ram.
   */
  object LeastRAM extends RouterType

  /**
   * A RouterType that select the connection where the actor has the least amount of messages in its mailbox.
   */
  object LeastMessages extends RouterType

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
    if (connections.size == 0)
      throw new IllegalArgumentException("To create a routed actor ref, at least one connection is required")

    val ref = routerType match {
      case RouterType.Direct ⇒
        if (connections.size > 1) throw new IllegalArgumentException("A direct router can't have more than 1 connection")
        new RoutedActorRef(actorAddress, connections) with Direct
      case RouterType.Random ⇒
        new RoutedActorRef(actorAddress, connections) with Random
      case RouterType.RoundRobin ⇒
        new RoutedActorRef(actorAddress, connections) with RoundRobin
      case _ ⇒ throw new IllegalArgumentException("Unsupported routerType " + routerType)
    }

    ref.start()
  }

  def actorOfWithRoundRobin(actorAddress: String, connections: Iterable[ActorRef]): ActorRef = {
    actorOf(actorAddress, connections, RoundRobin)
  }
}

/**
 * A RoutedActorRef is an ActorRef that has a set of connected ActorRef and it uses a Router to send a message to
 * on (or more) of these actors.
 */
class RoutedActorRef(val address: String, val cons: Iterable[ActorRef]) extends UnsupportedActorRef {
  this: Router ⇒

  def connections: Iterable[ActorRef] = cons

  override def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    route(message)(sender)
  }

  override def postMessageToMailboxAndCreateFutureResultWithTimeout(message: Any,
                                                                    timeout: Timeout,
                                                                    channel: UntypedChannel): Future[Any] = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    route[Any](message, timeout)(sender)
  }

  private[akka] def failOver(from: InetSocketAddress, to: InetSocketAddress): Unit = {
    throw new UnsupportedOperationException
  }

  def signalDeadActor(ref: ActorRef): Unit = {
    throw new UnsupportedOperationException
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
}

/**
 * The Router is responsible for sending a message to one (or more) of its connections.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Router {

  /**
   * Returns an Iterable containing all connected ActorRefs this Router uses to send messages to.
   */
  def connections: Iterable[ActorRef]

  /**
   * A callback this Router uses to indicate that some actorRef was not usable.
   *
   * Implementations should make sure that this method can be called without the actorRef being part of the
   * current set of connections. The most logical way to deal with this situation, is just to ignore it.
   *
   * @param ref the dead
   */
  def signalDeadActor(ref: ActorRef): Unit

  /**
   * Routes the message to one of the connections.
   */
  def route(message: Any)(implicit sender: Option[ActorRef]): Unit

  /**
   * Routes the message using a timeout to one of the connections and returns a Future to synchronize on the
   * completion of the processing of the message.
   */
  def route[T](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[T]
}

/**
 * An Abstract Router implementation that already provides the basic infrastructure so that a concrete
 * Router only needs to implement the next method.
 *
 * todo:
 * This also is the location where a failover  is done in the future if an ActorRef fails and a different
 * one needs to be selected.
 * todo:
 * this is also the location where message buffering should be done in case of failure.
 */
trait BasicRouter extends Router {

  def route(message: Any)(implicit sender: Option[ActorRef]): Unit = message match {
    case Routing.Broadcast(message) ⇒
      //it is a broadcast message, we are going to send to message to all connections.
      connections.foreach(actor ⇒
        try {
          actor.!(message)(sender)
        } catch {
          case e: Exception ⇒
            signalDeadActor(actor)
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
              signalDeadActor(actor)
              throw e

          }
        case None ⇒
          throwNoConnectionsError()
      }
  }

  def route[T](message: Any, timeout: Timeout)(implicit sender: Option[ActorRef]): Future[T] = message match {
    case Routing.Broadcast(message) ⇒
      throw new RoutingException("Broadcasting using a future for the time being is not supported")
    case _ ⇒
      //it no broadcast message, we are going to select an actor from the connections and send the message to him.
      next match {
        case Some(actor) ⇒
          try {
            actor.?(message, timeout)(sender).asInstanceOf[Future[T]]
          } catch {
            case e: Exception ⇒
              signalDeadActor(actor)
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
 * A Router that is used when a durable actor is used. All requests are send to the node containing the actor.
 * As soon as that instance fails, a different instance is created and since the mailbox is durable, the internal
 * state can be restored using event sourcing, and once this instance is up and running, all request will be send
 * to this instance.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Direct extends BasicRouter {

  lazy val next: Option[ActorRef] = {
    val connection = connections.headOption
    if (connection.isEmpty) EventHandler.warning(this, "Router has no replica connection")
    connection
  }
}

/**
 * A Router that randomly selects one of the target connections to send a message to.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Random extends BasicRouter {

  //todo: threadlocal random?
  private val random = new java.util.Random(System.currentTimeMillis)

  def next: Option[ActorRef] =
    if (connections.isEmpty) {
      EventHandler.warning(this, "Router has no replica connections")
      None
    } else {
      val randomIndex = random.nextInt(connections.size)

      //todo: possible index ouf of bounds problems since the number of connection could already have been changed.
      Some(connections.iterator.drop(randomIndex).next())
    }
}

/**
 * A Router that uses round-robin to select a connection.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait RoundRobin extends BasicRouter {

  private def items: List[ActorRef] = connections.toList

  //todo: this is broken since the list is not updated.
  private val current = new AtomicReference[List[ActorRef]](items)

  private def hasNext = connections.nonEmpty

  def next: Option[ActorRef] = {
    @tailrec
    def findNext: Option[ActorRef] = {
      val currentItems = current.get
      val newItems = currentItems match {
        case Nil ⇒ items
        case xs  ⇒ xs
      }

      if (newItems.isEmpty) {
        EventHandler.warning(this, "Router has no replica connections")
        None
      } else {
        if (current.compareAndSet(currentItems, newItems.tail)) newItems.headOption
        else findNext
      }
    }

    findNext
  }
}
