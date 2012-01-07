package akka.camel

import org.scalatest.matchers.ShouldMatchers
import akka.util.duration._
import org.apache.camel.ProducerTemplate
import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import akka.actor._
import akka.util.Timeout
import java.util.concurrent.{TimeUnit, CountDownLatch}

class ActivationAwareTest extends FlatSpec with ShouldMatchers with BeforeAndAfterEach{
  implicit var system :ActorSystem = _
  implicit val timeout = Timeout(10 seconds)
  def template : ProducerTemplate = camel.template
  def camel : Camel = CamelExtension(system)


  override protected def beforeEach() {
    system = ActorSystem(this.getClass.getSimpleName)
  }

  override protected def afterEach {
    system.shutdown()
  }

  def testActorWithEndpoint(uri: String): ActorRef = { system.actorOf(Props(new TestConsumer(uri)))}

  "ActivationAware" should "be notified when endpoint is activated" in {
    val actor = testActorWithEndpoint("direct:actor-1")
    try{
      ActivationAware.awaitActivation(actor, 10 second)
    } catch {
      case e : ActivationTimeoutException => fail("Failed to get notification within 1 second")
    }

    template.requestBody("direct:actor-1", "test") should be ("received test")
  }

  it should "be notified when endpoint is de-activated" in {
    val stopped = new CountDownLatch(1)
    val actor = start(new Consumer with ActivationAware{
      def endpointUri = "direct:a3"
      def receive = {case _ => {}}

      override def postStop() = {
        super.postStop()
        stopped.countDown()
      }
    })
    ActivationAware.awaitActivation(actor, 1 second)
    val awaitDeactivation = ActivationAware.registerInterestInDeActivation(actor, 1 second)
    system.stop(actor)
    awaitDeactivation()
    if(!stopped.await(1, TimeUnit.SECONDS)) fail("Actor should have stopped quickly after deactivation!")
  }

  it should "time out when waiting for endpoint de-activation for too long" in {
    val actor = start(new TestConsumer("direct:a5"))
    ActivationAware.awaitActivation(actor, 1 millis)
    val awaitDeactivation = ActivationAware.registerInterestInDeActivation(actor, 1 second)
    intercept[DeActivationTimeoutException]{
      awaitDeactivation()
    }
  }

  it should "consume activation messages first, so even if actor uses a _ wildcard, activation works fine" in {
    val actor = system.actorOf(Props(new Actor with ActivationAware{
      protected def receive = { case _ => /* consumes all*/ }
    }))
    actor ! EndpointActivated
    ActivationAware.awaitActivation(actor, 10 millis)
  }

  "awaitActivation" should "fail if notification timeout is too short and activation is not complete yet" in {
    val actor = testActorWithEndpoint("direct:actor-1")
    intercept[ActivationTimeoutException]{
      ActivationAware.awaitActivation(actor, 0 seconds)
    }
  }

  class TestConsumer(uri:String) extends Actor with Consumer with ActivationAware{
    def endpointUri = uri
    override def receive = {
      case msg:Message => sender ! "received " + msg.body
    }
  }


}