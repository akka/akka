/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import scala.collection.mutable
import akka.actor.{ LocalActorRef, Actor, ActorRef, Props, newUuid }
import akka.actor.Actor._
import akka.actor.ActorSystemImpl

/**
 * Stream of all kinds of network events, remote failure and connection events, cluster failure and connection events etc.
 * Also provides API for sender listener management.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object NetworkEventStream {

  private sealed trait NetworkEventStreamEvent

  private case class Register(listener: Listener, connectionAddress: RemoteAddress)
    extends NetworkEventStreamEvent

  private case class Unregister(listener: Listener, connectionAddress: RemoteAddress)
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

    val listeners = new mutable.HashMap[RemoteAddress, mutable.Set[Listener]]() {
      override def default(k: RemoteAddress) = mutable.Set.empty[Listener]
    }

    def receive = {
      case event: RemoteClientLifeCycleEvent ⇒
        listeners(event.remoteAddress) foreach (_ notify event)

      case event: RemoteServerLifeCycleEvent ⇒ // FIXME handle RemoteServerLifeCycleEvent

      case Register(listener, connectionAddress) ⇒
        listeners(connectionAddress) += listener

      case Unregister(listener, connectionAddress) ⇒
        listeners(connectionAddress) -= listener

      case _ ⇒ //ignore other
    }
  }
}

class NetworkEventStream(app: ActorSystemImpl) {

  import NetworkEventStream._

  // FIXME: check that this supervision is correct
  private[akka] val sender = app.provider.actorOf(app,
    Props[Channel].copy(dispatcher = app.dispatcherFactory.newPinnedDispatcher("NetworkEventStream")),
    app.systemGuardian, "network-event-sender", systemService = true)

  /**
   * Registers a network event stream listener (asyncronously).
   */
  def register(listener: Listener, connectionAddress: RemoteAddress) =
    sender ! Register(listener, connectionAddress)

  /**
   * Unregisters a network event stream listener (asyncronously) .
   */
  def unregister(listener: Listener, connectionAddress: RemoteAddress) =
    sender ! Unregister(listener, connectionAddress)
}
