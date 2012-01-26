/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import scala.collection.mutable
import akka.actor.{ Actor, Props, ActorSystemImpl }

/**
 * Stream of all kinds of network events, remote failure and connection events, cluster failure and connection events etc.
 * Also provides API for sender listener management.
 */
object NetworkEventStream {

  private sealed trait NetworkEventStreamEvent

  private case class Register(listener: Listener, connectionAddress: ParsedTransportAddress)
    extends NetworkEventStreamEvent

  private case class Unregister(listener: Listener, connectionAddress: ParsedTransportAddress)
    extends NetworkEventStreamEvent

  /**
   * Base trait for network event listener.
   */
  trait Listener {
    def notify(event: RemoteLifeCycleEvent)
  }

  /**
   * Channel actor with a registry of listeners.
   */
  private class Channel extends Actor {

    val listeners = new mutable.HashMap[ParsedTransportAddress, mutable.Set[Listener]]() {
      override def default(k: ParsedTransportAddress) = mutable.Set.empty[Listener]
    }

    def receive = {
      case event: RemoteClientLifeCycleEvent ⇒
        listeners(event.remoteAddress) foreach (_ notify event)

      case event: RemoteServerLifeCycleEvent ⇒ // FIXME handle RemoteServerLifeCycleEvent, ticket #1408 and #1190

      case Register(listener, connectionAddress) ⇒
        listeners(connectionAddress) += listener

      case Unregister(listener, connectionAddress) ⇒
        listeners(connectionAddress) -= listener

      case _ ⇒ //ignore other
    }
  }
}

class NetworkEventStream(system: ActorSystemImpl) {

  import NetworkEventStream._

  // FIXME: check that this supervision is correct, ticket #1408
  private[akka] val sender =
    system.systemActorOf(Props[Channel].withDispatcher("akka.remote.network-event-sender-dispatcher"), "network-event-sender")

  /**
   * Registers a network event stream listener (asyncronously).
   */
  def register(listener: Listener, connectionAddress: ParsedTransportAddress) =
    sender ! Register(listener, connectionAddress)

  /**
   * Unregisters a network event stream listener (asyncronously) .
   */
  def unregister(listener: Listener, connectionAddress: ParsedTransportAddress) =
    sender ! Unregister(listener, connectionAddress)
}
