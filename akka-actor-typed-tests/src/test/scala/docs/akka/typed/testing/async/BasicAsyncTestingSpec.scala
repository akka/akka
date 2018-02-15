package docs.akka.typed.testing.async

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed._
import akka.testkit.typed.scaladsl.{ TestKit, _ }
import org.scalatest._

object BasicAsyncTestingSpec {
  //#under-test
  case class Ping(msg: String, response: ActorRef[Pong])
  case class Pong(msg: String)

  val echoActor = Behaviors.immutable[Ping] { (_, msg) ⇒
    msg match {
      case Ping(m, replyTo) ⇒
        replyTo ! Pong(m)
        Behaviors.same
    }
  }
  //#under-test
}

//#test-header
class BasicAsyncTestingSpec extends TestKit with TypedAkkaSpecWithShutdown {
  //#test-header

  import BasicAsyncTestingSpec._

  "A testkit" must {
    "support verifying a response" in {
      //#test-spawn
      val probe = TestProbe[Pong]()
      val pinger = spawn(echoActor, "ping")
      pinger ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
      //#test-spawn
    }

    "support verifying a response - anonymous" in {
      //#test-spawn-anonymous
      val probe = TestProbe[Pong]()
      val pinger = spawn(echoActor)
      pinger ! Ping("hello", probe.ref)
      probe.expectMessage(Pong("hello"))
      //#test-spawn-anonymous
    }
  }

  //#test-shutdown
  override def afterAll(): Unit = shutdownTestKit()
  //#test-shutdown
}
