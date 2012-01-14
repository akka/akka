package akka.camel


import akka.actor.{Props, ActorSystem, Actor}
import akka.util.Duration
import akka.util.duration._
import java.util.concurrent.{ExecutionException, TimeUnit}
import org.apache.camel.CamelContext
import org.scalatest.junit.JUnitSuite
import org.junit.{After, Before}

object TestSupport {
  def start(actor: => Actor)(implicit system : ActorSystem) = {
      val actorRef = system.actorOf(Props(actor))
      CamelExtension(system).awaitActivation(actorRef, 1 second)
      actorRef
    }
  
  
    private[camel] implicit def camelToTestWrapper(camel:Camel) = new CamelTestWrapper(camel)
  
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
  
    trait MessageSugar{
      def camel : Camel
      def Message(body:Any) = akka.camel.Message(body, Map.empty, camel.context)
      def Message(body:Any, headers:Map[String, Any]) = akka.camel.Message(body, headers, camel.context)
  
    }
  
    //TODO replace with object as it is too slow to start camel for every test
    trait CamelSupport{ this: JUnitSuite =>
      implicit def context : CamelContext = camel.context
      var camel : Camel = _
      var camelSystem : ActorSystem = _
  
      @Before def before = {
        camelSystem = ActorSystem("test")
        camel = CamelExtension(camelSystem)
      }
  
      @After def after() = {
        camelSystem.shutdown()
      }
    }
          
}