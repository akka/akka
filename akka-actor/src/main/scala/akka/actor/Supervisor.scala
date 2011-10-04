/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

object Supervisor {

  class Supervisor(terminationHandling: (ActorContext, Terminated) ⇒ Unit) extends Actor {
    def receive = {
      case t: Terminated ⇒ terminationHandling(context, t)
    }
  }

  private val doNothing: (ActorContext, Terminated) ⇒ Unit = (_, _) ⇒ ()

  def apply(faultHandler: FaultHandlingStrategy = Props.defaultFaultHandler, supervisor: ActorRef = null,
            terminationHandling: (ActorContext, Terminated) ⇒ Unit = doNothing): ActorRef =
    Actor.actorOf(Props(new Supervisor(terminationHandling)).withSupervisor(supervisor).withFaultHandler(faultHandler))
}