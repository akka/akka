/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.patterns

import se.scalablesolutions.akka.actor.Actor

sealed trait ListenerMessage
case class Listen(listener : Actor) extends ListenerMessage
case class Deafen(listener : Actor) extends ListenerMessage 
case class WithListeners(f : Set[Actor] => Unit) extends ListenerMessage

trait Listeners { self : Actor =>
  import se.scalablesolutions.akka.actor.Agent
  private lazy val listeners = Agent(Set[Actor]())

  protected def listenerManagement : PartialFunction[Any,Unit] = {
    case Listen(l) => listeners( _ + l)
    case Deafen(l) => listeners( _ - l )
    case WithListeners(f) => listeners foreach f
  }
  
  protected def gossip(msg : Any) = listeners foreach ( _ foreach ( _ ! msg ) )
}