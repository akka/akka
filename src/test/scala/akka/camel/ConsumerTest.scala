package akka.camel

import akka.actor._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers.{eq => the, any}
import java.util.concurrent.TimeoutException

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
  it should "fail if camel is not started"

  it should "never get activation message, if endpoint is invalid" in {
    withCamel{ camel =>
      intercept[TimeoutException]{
        start(new TestActor(uri="some invalid uri"))
      }
    }
  }

  it should  "verify that from(...) was called" in pending
//  in{
    //TODO: fix it later
//    withCamel{ camel =>
//      intercept[ConsumerRequiresFromEndpointException]{
//        system.actorOf(Props(new Actor with Consumer{
//          protected def receive = {case _ => {}}
//        }))
//      }
//    }
//  }

  it should  "support in-out messaging" in  {
    withCamel{ camel =>
      start(new Actor with Consumer with ActivationAware{
        def endpointUri = "direct:a1"
        protected def receive = { case m: Message => sender ! "received "+m.bodyAs[String]}
      })
      camel.template.requestBody("direct:a1", "some message") should be ("received some message")
    }
  }

  //TODO: slow consumer case for out-capable
  //TODO: when consumer throws while processing

  it should "process messages even after actor restart" in {

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