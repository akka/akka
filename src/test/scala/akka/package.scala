package akka

import actor.{Props, ActorSystem, Actor}
import util.Duration
import util.duration._
import java.util.concurrent.{ExecutionException, TimeUnit}

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


  implicit def camelToTestWrapper(camel:Camel) = new CamelTestWrapper(camel)

  class CamelTestWrapper(camel:Camel){
    /**
     * Sends msg to the endpoint and returns response.
     * It only waits for the response until timeout passes.
     * This is to reduce cases when unit-tests block infinitely.
     */
    def sendTo(to: String, msg: String, timeout:Duration = 1 second): AnyRef = {
      try{
        camel.template.asyncRequestBody(to, msg).get(timeout.toNanos, TimeUnit.NANOSECONDS)
      }catch{
        case e : ExecutionException => throw e.getCause
      }
    }

    def routeCount = camel.context.getRoutes().size()
  }

}