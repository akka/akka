package akka.testkit

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import akka.actor._
import akka.event.EventHandler
import akka.dispatch.Future
import akka.util.duration._

class TestProbeSpec extends AkkaSpec {

  "A TestProbe" must {

    "reply to futures" in {
      val tk = TestProbe()
      val future = tk.ref ? "hello"
      tk.expectMsg(0 millis, "hello") // TestActor runs on CallingThreadDispatcher
      tk.reply("world")
      future must be('completed)
      future.get must equal("world")
    }

    "reply to messages" in {
      val tk1 = TestProbe()
      val tk2 = TestProbe()
      tk1.ref.!("hello")(tk2.ref)
      tk1.expectMsg(0 millis, "hello")
      tk1.reply("world")
      tk2.expectMsg(0 millis, "world")
    }

    "properly send and reply to messages" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      probe1.send(probe2.ref, "hello")
      probe2.expectMsg(0 millis, "hello")
      probe2.reply("world")
      probe1.expectMsg(0 millis, "world")
    }

  }

}
