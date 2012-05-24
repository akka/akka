package akka.remote.testconductor

import akka.remote.AkkaRemoteSpec
import com.typesafe.config.ConfigFactory
import akka.remote.AbstractRemoteActorMultiJvmSpec
import akka.actor.Props
import akka.actor.Actor
import akka.dispatch.Await
import akka.dispatch.Await.Awaitable
import akka.util.Duration
import akka.util.duration._
import akka.testkit.ImplicitSender
import java.net.InetSocketAddress
import java.net.InetAddress
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.MultiNodeConfig

object TestConductorMultiJvmSpec extends MultiNodeConfig {
  commonConfig(ConfigFactory.parseString("""
    # akka.loglevel = DEBUG
    akka.remote {
      log-received-messages = on
      log-sent-messages = on
    }
    akka.actor.debug {
      receive = on
      fsm = on
    }
  """))
  
  val master = role("master")
  val slave = role("slave")
}

class TestConductorMultiJvmNode1 extends TestConductorSpec
class TestConductorMultiJvmNode2 extends TestConductorSpec

class TestConductorSpec extends MultiNodeSpec(TestConductorMultiJvmSpec) with ImplicitSender {
  
  import TestConductorMultiJvmSpec._

  def initialParticipants = 2

  runOn(master) {
    system.actorOf(Props(new Actor {
      def receive = {
        case x ⇒ testActor ! x; sender ! x
      }
    }), "echo")
  }

  val echo = system.actorFor(node(master) / "user" / "echo")

  "A TestConductor" must {

    "enter a barrier" in {
      testConductor.enter("name")
    }

    "support throttling of network connections" in {

      runOn(slave) {
        // start remote network connection so that it can be throttled
        echo ! "start"
      }

      expectMsg("start")

      runOn(master) {
        testConductor.throttle(slave, master, Direction.Send, rateMBit = 0.01).await
      }

      testConductor.enter("throttled_send")

      runOn(slave) {
        for (i ← 0 to 9) echo ! i
      }

      within(0.6 seconds, 2 seconds) {
        expectMsg(500 millis, 0)
        receiveN(9) must be(1 to 9)
      }

      testConductor.enter("throttled_send2")

      runOn(master) {
        testConductor.throttle(slave, master, Direction.Send, -1).await
        testConductor.throttle(slave, master, Direction.Receive, rateMBit = 0.01).await
      }

      testConductor.enter("throttled_recv")

      runOn(slave) {
        for (i ← 10 to 19) echo ! i
      }

      val (min, max) =
        ifNode(master) {
          (0 seconds, 500 millis)
        } {
          (0.6 seconds, 2 seconds)
        }

      within(min, max) {
        expectMsg(500 millis, 10)
        receiveN(9) must be(11 to 19)
      }

      testConductor.enter("throttled_recv2")

      runOn(master) {
        testConductor.throttle(slave, master, Direction.Receive, -1).await
      }
    }

  }

}
