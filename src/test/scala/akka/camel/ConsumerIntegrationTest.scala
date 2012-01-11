package akka.camel

import akka.actor._
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers.{eq => the, any}
import akka.util.duration._
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit._
import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import org.apache.camel.{FailedToCreateRouteException, CamelExecutionException}
import akka.util.Duration

class ConsumerIntegrationTest extends FlatSpec with ShouldMatchers with MockitoSugar with BeforeAndAfterEach{
  implicit var system : ActorSystem = _


  override protected def beforeEach() {
    system = ActorSystem("test")
  }

  override protected def afterEach() {
    system.shutdown()
  }

  class TestActor(uri:String = "file://abcde") extends Actor with Consumer{
    def endpointUri = uri
    protected def receive = { case _ =>  println("foooo..")}
  }

  "Consumer" should "register itself with Camel during initialization" in{
    val mockCamel = mock[Camel]

    CamelExtension.setCamelFor(system, mockCamel)

    system.actorOf(Props(new TestActor("file://abc")))
    Thread.sleep(300)
    verify(mockCamel).registerConsumer(the("file://abc"), any[TestActor], any[Duration])
  }

  //TODO: decide on Camel lifecycle. Ideally it should prevent creating non-started instances, so there is no need to test if consumers fail when Camel is not initialized.
  it should "fail if camel is not started" in (pending)

  it should "throw FailedToCreateRouteException, if endpoint is invalid" in {
    intercept[FailedToCreateRouteException]{
      start(new TestActor( uri="some invalid uri"))
    }
  }

  def _camel: Camel = {
    CamelExtension(system)
  }

  it should  "support in-out messaging" in  {
    start(new Consumer {
      def endpointUri = "direct:a1"
      protected def receive = {
        case m: Message => sender ! "received "+m.bodyAs[String]
      }
    })
    _camel.sendTo("direct:a1", msg="some message") should be ("received some message")
  }
  it should "time-out if consumer is slow" in {
    val SHORT_TIMEOUT = 10 millis
    val LONG_WAIT = 200 millis

    start(new Consumer{
      override def outTimeout = SHORT_TIMEOUT

      def endpointUri = "direct:a3"
      protected def receive = { case _ => { Thread.sleep(LONG_WAIT.toMillis); sender ! "done" } }
    })

    intercept[CamelExecutionException]{
      _camel.sendTo("direct:a3", msg = "some msg 3")
    }
  }

  it should "process messages even after actor restart" in {
    val restarted = new CountDownLatch(1)
    val consumer = start(new Consumer{
      def endpointUri = "direct:a2"

      protected def receive = {
        case "throw" => throw new Exception
        case m:Message => sender ! "received "+m.bodyAs[String]
      }

      override def postRestart(reason: Throwable) {
        println("RESTARTED!!!")
        restarted.countDown()
      }
    })
    consumer ! "throw"
    if(!restarted.await(5, SECONDS)) fail("Actor failed to restart!")

    val response = _camel.sendTo("direct:a2", msg = "xyz")
    response should be ("received xyz")
  }


  it should  "unregister itself when stopped - integration test" in {
    val actorRef = start(new TestActor())
    _camel.awaitActivation(actorRef, 1 second)

    _camel.routeCount should be >(0)


    system.stop(actorRef)
    _camel.awaitDeactivation(actorRef, 1 second )

    _camel.routeCount should be (0)
  }

}