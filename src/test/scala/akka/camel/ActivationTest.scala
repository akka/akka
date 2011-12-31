package akka.camel

import org.scalatest.matchers.ShouldMatchers
import akka.util.duration._
import java.util.concurrent.TimeoutException
import org.apache.camel.ProducerTemplate
import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import akka.actor._
import akka.util.Timeout

class ActivationTest extends FlatSpec with ShouldMatchers with BeforeAndAfterEach{
  var system :ActorSystem = _
  implicit val timeout = Timeout(10 seconds)
  var template : ProducerTemplate = _

  //TODO: handle camel service lifecycle

  override protected def beforeEach() {
    Camel.start
    system = ActorSystem("test")
    template = Camel.template
  }

  override protected def afterEach {
    system.shutdown()
    Camel.stop
  }

  def testActorWithEndpoint(uri: String): ActorRef = { system.actorOf(Props(new TestConsumer(uri)))}

  "ActivationAware" should "be notified when activated" in {
    val actor = testActorWithEndpoint("direct:actor-1")
    try{
      ActivationAware.awaitActivation(actor, 3 second)
    } catch {
      case e : TimeoutException => fail("Failed to get notification within 1 second")
    }

    template.requestBody("direct:actor-1", "test") should be ("received test")
  }

  it should "fail if notification timeout is too short and activation is not complete yet" in {
    val actor = testActorWithEndpoint("direct:actor-1")
    intercept[TimeoutException]{
      ActivationAware.awaitActivation(actor, 0 seconds)
    }
  }

  class TestConsumer(uri:String) extends Actor with Consumer with ActivationAware{
    from(uri)
    override def receive = {
      case msg:Message => sender ! "received " + msg.body
    }
  }
}