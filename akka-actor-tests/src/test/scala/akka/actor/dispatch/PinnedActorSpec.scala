package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.testkit._
import akka.actor.{ Props, Actor }
import akka.testkit.AkkaSpec
import org.scalatest.BeforeAndAfterEach
import akka.dispatch.{ Block, PinnedDispatcher, Dispatchers }

object PinnedActorSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello"   ⇒ sender ! "World"
      case "Failure" ⇒ throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class PinnedActorSpec extends AkkaSpec with BeforeAndAfterEach with DefaultTimeout {
  import PinnedActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  "A PinnedActor" must {

    "support tell" in {
      var oneWay = new CountDownLatch(1)
      val actor = system.actorOf(Props(self ⇒ { case "OneWay" ⇒ oneWay.countDown() }).withDispatcher(system.dispatcherFactory.newPinnedDispatcher("test")))
      val result = actor ! "OneWay"
      assert(oneWay.await(1, TimeUnit.SECONDS))
      actor.stop()
    }

    "support ask/reply" in {
      val actor = system.actorOf(Props[TestActor].withDispatcher(system.dispatcherFactory.newPinnedDispatcher("test")))
      assert("World" === Block.sync(actor ? "Hello", timeout.duration))
      actor.stop()
    }
  }
}
