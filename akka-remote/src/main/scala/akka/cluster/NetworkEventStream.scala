/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.dispatch.PinnedDispatcher

import scala.collection.mutable

import java.net.InetSocketAddress
import akka.actor.{ LocalActorRef, Actor, ActorRef, Props, newUuid }
import akka.actor.Actor._

/**
 * Stream of all kinds of network events, remote failure and connection events, cluster failure and connection events etc.
 * Also provides API for channel listener management.
 */
object NetworkEventStream {

  private sealed trait NetworkEventStreamEvent

  private case class Register(listener: Listener, connectionAddress: InetSocketAddress)
    extends NetworkEventStreamEvent

  private case class Unregister(listener: Listener, connectionAddress: InetSocketAddress)
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

    val listeners = new mutable.HashMap[InetSocketAddress, mutable.Set[Listener]]() {
      override def default(k: InetSocketAddress) = mutable.Set.empty[Listener]
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

  private[akka] val channel = new LocalActorRef(Props[Channel].copy(dispatcher = new PinnedDispatcher()), newUuid.toString, systemService = true)

  /**
   * Registers a network event stream listener (asyncronously).
   */
  def register(listener: Listener, connectionAddress: InetSocketAddress) =
    channel ! Register(listener, connectionAddress)

  /**
   * Unregisters a network event stream listener (asyncronously) .
   */
  def unregister(listener: Listener, connectionAddress: InetSocketAddress) =
    channel ! Unregister(listener, connectionAddress)
}
