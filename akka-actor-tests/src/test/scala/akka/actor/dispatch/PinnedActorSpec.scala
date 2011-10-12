package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.event.EventHandler
import akka.testkit.TestEvent._
import akka.testkit.EventFilter
import akka.dispatch.{ PinnedDispatcher, Dispatchers }
import akka.actor.{ Props, Actor }
import akka.testkit.AkkaSpec
import org.scalatest.BeforeAndAfterEach

object PinnedActorSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello"   ⇒ reply("World")
      case "Failure" ⇒ throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }
}

class PinnedActorSpec extends AkkaSpec with BeforeAndAfterEach {
  import PinnedActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  override def beforeEach {
    EventHandler.notify(Mute(EventFilter[RuntimeException]("Failure")))
  }

  override def afterEach {
    EventHandler.notify(UnMuteAll)
  }

  "A PinnedActor" must {

    "support tell" in {
      var oneWay = new CountDownLatch(1)
      val actor = createActor(Props(self ⇒ { case "OneWay" ⇒ oneWay.countDown() }).withDispatcher(app.dispatcherFactory.newPinnedDispatcher("test")))
      val result = actor ! "OneWay"
      assert(oneWay.await(1, TimeUnit.SECONDS))
      actor.stop()
    }

    "support ask/reply" in {
      val actor = createActor(Props[TestActor].withDispatcher(app.dispatcherFactory.newPinnedDispatcher("test")))
      val result = (actor ? "Hello").as[String]
      assert("World" === result.get)
      actor.stop()
    }

    "support ask/exception" in {
      val actor = createActor(Props[TestActor].withDispatcher(app.dispatcherFactory.newPinnedDispatcher("test")))
      EventHandler.notify(Mute(EventFilter[RuntimeException]("Expected exception; to test fault-tolerance")))
      try {
        (actor ? "Failure").get
        fail("Should have thrown an exception")
      } catch {
        case e ⇒
          assert("Expected exception; to test fault-tolerance" === e.getMessage())
      }
      actor.stop()
    }
  }
}
