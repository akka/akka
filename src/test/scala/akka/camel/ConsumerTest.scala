package akka.camel

import akka.actor._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers.{eq => the, any}
import java.util.concurrent.TimeUnit
import akka.util.duration._
import org.apache.camel.CamelExecutionException

class ConsumerScalaTest extends FlatSpec with ShouldMatchers with MockitoSugar{
  implicit val system = ActorSystem("test")

  class TestActor(_camel : ConsumerRegistry = Camel.instance,  uri:String = "file://abcde") extends Actor with Consumer with ActivationAware{
    override lazy val camel = _camel
    def endpointUri = uri
    protected def receive = { case _ =>  println("foooo..")}
  }


  "Consumer" should "register itself with Camel during initialization" in{
    val mockCamel = mock[ConsumerRegistry]

    system.actorOf(Props(new TestActor(mockCamel, "file://abc")))

    verify(mockCamel).registerConsumer(the("file://abc"), any[TestActor])
  }

  //TODO: decide on Camel lifecycle. Ideally it should prevent creating non-started instances, so there is no need to test if consumers fail when Camel is not initialized.
  it should "fail if camel is not started" in (pending)

  it should "never get activation message, if endpoint is invalid" in {
    withCamel{ camel =>
      intercept[ActivationTimeoutException]{
        start(new TestActor(uri="some invalid uri"))
      }
    }
  }

  it should  "support in-out messaging" in  {
    withCamel{ camel =>
      start(new Consumer with ActivationAware{
        def endpointUri = "direct:a1"
        protected def receive = { case m: Message => sender ! "received "+m.bodyAs[String]}
      })
      camel.template.requestBody("direct:a1", "some message") should be ("received some message")
    }
  }

  //TODO: slow consumer case for out-capable
  it should "time-out if consumer is slow" in {
    val SHORT_TIMEOUT = 10 millis
    val LONG_WAIT = 200

    withCamel{ camel =>
      start(new Consumer with ActivationAware{
        override def outTimeout = SHORT_TIMEOUT

        def endpointUri = "direct:a3"
        protected def receive = {
          case _ => {
            Thread.sleep(LONG_WAIT); sender ! "done" }
        }
      })

      intercept[CamelExecutionException]{
        camel.template.requestBody("direct:a3", "some msg") should be("done")
      }
    }
  }
  //TODO: when consumer throws while processing

  it should "process messages even after actor restart" in {
    withCamel{ camel =>
      var restarted = false
      val consumer = start(new Consumer with ActivationAware{
        def endpointUri = "direct:a2"

        protected def receive = {
          case "throw" => throw new Exception
          case m:Message => sender ! "received "+m.bodyAs[String]          
        }

        override def preRestart(reason: Throwable, message: Option[Any]) {restarted = true}
      })
      consumer ! "throw"
      val response = camel.template.asyncRequestBody("direct:a2", "xyz").get(1L, TimeUnit.SECONDS)
      response should be ("received xyz")
      restarted should be (true)
    }
  }


  it should  "unregister itself when stopped - integration test" in {
    withCamel{ camel =>
      val actorRef = start(new TestActor())

      camel.context.getRoutes().size() should be >(0)
      system.stop(actorRef)

      Thread.sleep(500)

      camel.context.getRoutes.size() should be (0)
    }
  }

}