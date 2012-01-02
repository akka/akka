package akka

import actor.{Props, ActorSystem, Actor}
import util.duration._

package object camel{
  def withCamel(block: Camel => Unit) = {
    Camel.start
    try{
      block(Camel.instance)
    }
    finally {
      Camel.stop
    }

  }


  def start(actor: => Actor)(implicit system : ActorSystem) = {
    val actorRef = system.actorOf(Props(actor))
    ActivationAware.awaitActivation(actorRef, 1 second)
    actorRef
  }


}