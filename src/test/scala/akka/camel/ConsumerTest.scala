package akka.camel

import akka.actor._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers.{eq => the, any}
import akka.util.duration._

class ConsumerScalaTest extends FlatSpec with ShouldMatchers with MockitoSugar{
  val system = ActorSystem("test")

  class TestActor(_camel : ConsumerRegistry = Camel.instance,  uri:String = "file://abcde") extends Actor with Consumer with ActivationAware{
    override lazy val camel = _camel
    from(uri)
    protected def receive = { case _ =>  println("foooo..")}
  }

  def start(actor: => Actor) = {
    val actorRef = system.actorOf(Props(actor))
    ActivationAware.awaitActivation(actorRef, 1 second)
    actorRef
  }

  "Consumer" should "register itself with Camel during initialization" in{
    val mockCamel = mock[ConsumerRegistry]

    system.actorOf(Props(new TestActor(mockCamel, "file://abc")))

    verify(mockCamel).registerConsumer(the("file://abc"), any[TestActor])
  }

  //TODO: decide on Camel lifecycle. Ideally it should prevent creating non-started instances, so there is no need to test if consumers fail when Camel is not initialized.
  it should "fail if camel is not started"

  it should "fail if endpoint is invalid"
  it should  "verify that from(...) was called"
  it should  "support in-out messaging" in  {
    withCamel{
      start(new Actor with Consumer with ActivationAware{
        from("direct:a1")

        protected def receive = {
          case m : Message => sender ! "received "+m.bodyAs[String]
        }
      })
      Camel.template.requestBody("direct:a1", "some message") should be ("received some message")
    }

  }
  
  
  
  //TODO: slow consumer case for out-capable
  //TODO: when consumer throws while processing
  //TODO: what about actor restarts?


  it should  "unregister itself when stopped - integration test" in {
    withCamel{
      val actorRef = start(new TestActor())

      Camel.context.getRoutes().size() should be >(0)
      system.stop(actorRef)

      Thread.sleep(500)

      Camel.context.getRoutes.size() should be (0)
    }
  }

  def withCamel(block: => Unit) = {
    Camel.start
    try{
      block
    }
    finally {
      Camel.stop
    }

  }
}