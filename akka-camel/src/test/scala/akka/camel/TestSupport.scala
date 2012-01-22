package akka.camel


import akka.actor.{Props, ActorSystem, Actor}
import akka.util.Duration
import akka.util.duration._
import java.util.concurrent.{ExecutionException, TimeUnit}
import org.scalatest.{BeforeAndAfterAll, Suite}

private[camel] object TestSupport {

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


  trait SharedCamelSystem extends BeforeAndAfterAll{ this:Suite =>
    implicit lazy val system = ActorSystem("test")
    implicit def camel = CamelExtension(system)

    abstract override protected def afterAll() {
      super.afterAll()
      system.shutdown() 
    }
  }

  def time[A](block : => A) : Duration ={
    val start = System.currentTimeMillis()
    block
    val duration = System.currentTimeMillis() - start
    duration millis
  }



}