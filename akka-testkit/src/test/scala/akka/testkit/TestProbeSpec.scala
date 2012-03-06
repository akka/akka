package akka.testkit

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, WordSpec }
import akka.actor._
import akka.util.duration._
import akka.dispatch.{ Await, Future }
import akka.pattern.ask

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TestProbeSpec extends AkkaSpec with DefaultTimeout {

  "A TestProbe" must {

    "reply to futures" in {
      val tk = TestProbe()
      val future = tk.ref ? "hello"
      tk.expectMsg(0 millis, "hello") // TestActor runs on CallingThreadDispatcher
      tk.lastMessage.sender ! "world"
      future must be('completed)
      Await.result(future, timeout.duration) must equal("world")
    }

    "reply to messages" in {
      val tk1 = TestProbe()
      val tk2 = TestProbe()
      tk1.ref.!("hello")(tk2.ref)
      tk1.expectMsg(0 millis, "hello")
      tk1.lastMessage.sender ! "world"
      tk2.expectMsg(0 millis, "world")
    }

    "properly send and reply to messages" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      probe1.send(probe2.ref, "hello")
      probe2.expectMsg(0 millis, "hello")
      probe2.lastMessage.sender ! "world"
      probe1.expectMsg(0 millis, "world")
    }

    "have an AutoPilot" in {
      //#autopilot
      val probe = TestProbe()
      probe.setAutoPilot(new TestActor.AutoPilot {
        def run(sender: ActorRef, msg: Any): Option[TestActor.AutoPilot] =
          msg match {
            case "stop" ⇒ None
            case x      ⇒ testActor.tell(x, sender); Some(this)
          }
      })
      //#autopilot
      probe.ref ! "hallo"
      probe.ref ! "welt"
      probe.ref ! "stop"
      expectMsg("hallo")
      expectMsg("welt")
      probe.expectMsg("hallo")
      probe.expectMsg("welt")
      probe.expectMsg("stop")
      probe.ref ! "hallo"
      probe.expectMsg("hallo")
      testActor ! "end"
      expectMsg("end") // verify that "hallo" did not get through
    }

  }

}
