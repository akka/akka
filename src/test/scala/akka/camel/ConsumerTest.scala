package akka.camel

import akka.actor._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers.{eq => the, any}

class ConsumerScalaTest extends FlatSpec with ShouldMatchers with MockitoSugar{

  val system = ActorSystem("test")

  "Consumer" should "register itself with Camel during initialization" in{
    val mockCamel = mock[ConsumerRegistry]

    class TestActor extends Actor with Consumer {
          override lazy val camel = mockCamel
          from("file://abc")
          protected def receive = { case _ =>  println("foooo..")}
        }
    system.actorOf(Props(new TestActor()))

    verify(mockCamel).registerConsumer(the("file://abc"), any[TestActor])
  }
  
  it should "fail if camel not started"
  it should "fail if endpoint is invalid"
  it should  "verify that from(...) was called"
  it should  "support in-out messaging"
}