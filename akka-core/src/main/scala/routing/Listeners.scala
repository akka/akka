/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.patterns

import se.scalablesolutions.akka.actor.{Actor, ActorRef}

sealed trait ListenerMessage
case class Listen(listener: ActorRef) extends ListenerMessage
case class Deafen(listener: ActorRef) extends ListenerMessage 
case class WithListeners(f: Set[ActorRef] => Unit) extends ListenerMessage

/** Listeners is a generic trait to implement listening capability on an Actor
 *  Use the <code>gossip(msg)</code> method to have it sent to the listenees
 *  Send <code>Listen(self)</code> to start listening
 *  Send <code>Deafen(self)</code> to stop listening
 *  Send <code>WithListeners(fun)</code> to traverse the current listeners
 */
trait Listeners { self : Actor =>
  import se.scalablesolutions.akka.actor.Agent
  private lazy val listeners = Agent(Set[ActorRef]())

  protected def listenerManagement : Receive = {
    case Listen(l) => listeners( _ + l)
    case Deafen(l) => listeners( _ - l )
    case WithListeners(f) => listeners foreach f
  }
  
  protected def gossip(msg : Any) = listeners foreach ( _ foreach ( _ ! msg ) )
}