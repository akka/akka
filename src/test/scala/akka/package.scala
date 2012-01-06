package akka

import actor.{Props, ActorSystem, Actor}
import util.Duration
import util.duration._
import java.util.concurrent.{ExecutionException, TimeUnit}
import org.apache.camel.CamelContext
import org.junit.{After, Before}
import org.scalatest.junit.JUnitSuite

package object camel{
  def withCamel(block: DefaultCamel => Unit) = {
    val camel = new DefaultCamel().start
    try{
      block(camel)
    }
    finally {
      camel.stop
    }
  }

  def start(actor: => Actor)(implicit system : ActorSystem) = {
    val actorRef = system.actorOf(Props(actor))
    ActivationAware.awaitActivation(actorRef, 1 second)
    actorRef
  }


  implicit def camelToTestWrapper(camel:DefaultCamel) = new CamelTestWrapper(camel)

  class CamelTestWrapper(camel:DefaultCamel){
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

  trait MessageSugar{
    def camel : DefaultCamel
    def Message(body:Any) = akka.camel.Message(body, Map.empty, camel)
    def Message(body:Any, headers:Map[String, Any]) = akka.camel.Message(body, headers, camel)

  }

  trait CamelSupport{ this: JUnitSuite =>
    implicit def context : CamelContext = camel.context
    var camel : DefaultCamel = _
    @Before def before = camel = new DefaultCamel().start
    @After def after() = camel.stop
  }

}