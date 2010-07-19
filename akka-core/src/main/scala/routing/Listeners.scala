/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.routing

import se.scalablesolutions.akka.actor.{Actor, ActorRef}

import java.util.concurrent.CopyOnWriteArraySet

sealed trait ListenerMessage
case class Listen(listener: ActorRef) extends ListenerMessage
case class Deafen(listener: ActorRef) extends ListenerMessage
case class WithListeners(f: List[ActorRef] => Unit) extends ListenerMessage

/**
 * Listeners is a generic trait to implement listening capability on an Actor.
 * <p/>
 * Use the <code>gossip(msg)</code> method to have it sent to the listeners.
 * <p/>
 * Send <code>Listen(self)</code> to start listening.
 * <p/>
 * Send <code>Deafen(self)</code> to stop listening.
 * <p/>
 * Send <code>WithListeners(fun)</code> to traverse the current listeners.
 */
trait Listeners { self: Actor =>
  private val listeners = new CopyOnWriteArraySet[ActorRef]

  protected def listenerManagement: Receive = {
    case Listen(l)        => listeners add l
    case Deafen(l)        => listeners remove l
    case WithListeners(f) => f(listenersAsList)
  }

  protected def gossip(msg: Any) = listenersAsList foreach (_ ! msg)

  private def listenersAsList: List[ActorRef] = listeners.toArray.toList.asInstanceOf[List[ActorRef]]
}
