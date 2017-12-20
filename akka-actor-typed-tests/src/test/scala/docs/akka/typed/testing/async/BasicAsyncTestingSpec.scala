package docs.akka.typed.testing.async

import akka.actor.typed.scaladsl.Actor
import akka.actor.typed._
import akka.typed.testkit.TestKit
import akka.typed.testkit.scaladsl._
import org.scalatest._

object BasicAsyncTestingSpec {
  //#under-test
  case class Ping(msg: String, response: ActorRef[Pong])
  case class Pong(msg: String)

  val echoActor = Actor.immutable[Ping] { (_, msg) ⇒
    msg match {
      case Ping(m, replyTo) ⇒
        replyTo ! Pong(m)
        Actor.same
    }
  }
  //#under-test
}

//#test-header
class BasicAsyncTestingSpec extends TestKit(ActorSystem(Actor.empty, "BasicTestingSpec"))
  with WordSpecLike with BeforeAndAfterAll {
  //#test-header

  import BasicAsyncTestingSpec._

  "A testkit" must {
    "support verifying a response" in {
      //#test
      val probe = TestProbe[Pong]()
      val pinger = actorOf(echoActor, "ping")
      pinger ! Ping("hello", probe.ref)
      probe.expectMsg(Pong("hello"))
      //#test
    }
  }

  //#test-shutdown
  override def afterAll(): Unit = shutdown()
  //#test-shutdown
}
