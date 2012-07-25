/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import com.typesafe.config.ConfigFactory
import akka.actor.Props
import akka.actor.Actor
import scala.concurrent.Await
import scala.concurrent.Awaitable
import scala.concurrent.util.Duration
import scala.concurrent.util.duration._
import akka.testkit.ImplicitSender
import akka.testkit.LongRunningTest
import java.net.InetSocketAddress
import java.net.InetAddress
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.MultiNodeConfig

object TestConductorMultiJvmSpec extends MultiNodeConfig {
  commonConfig(debugConfig(on = false))

  val master = role("master")
  val slave = role("slave")
}

class TestConductorMultiJvmNode1 extends TestConductorSpec
class TestConductorMultiJvmNode2 extends TestConductorSpec

class TestConductorSpec extends MultiNodeSpec(TestConductorMultiJvmSpec) with ImplicitSender {

  import TestConductorMultiJvmSpec._

  def initialParticipants = 2

  lazy val echo = system.actorFor(node(master) / "user" / "echo")

  "A TestConductor" must {

    "enter a barrier" taggedAs LongRunningTest in {
      runOn(master) {
        system.actorOf(Props(new Actor {
          def receive = {
            case x ⇒ testActor ! x; sender ! x
          }
        }), "echo")
      }

      enterBarrier("name")
    }

    "support throttling of network connections" taggedAs LongRunningTest in {

      runOn(slave) {
        // start remote network connection so that it can be throttled
        echo ! "start"
      }

      expectMsg("start")

      runOn(master) {
        testConductor.throttle(slave, master, Direction.Send, rateMBit = 0.01).await
      }

      enterBarrier("throttled_send")

      runOn(slave) {
        for (i ← 0 to 9) echo ! i
      }

      within(0.6 seconds, 2 seconds) {
        expectMsg(500 millis, 0)
        receiveN(9) must be(1 to 9)
      }

      enterBarrier("throttled_send2")

      runOn(master) {
        testConductor.throttle(slave, master, Direction.Send, -1).await
        testConductor.throttle(slave, master, Direction.Receive, rateMBit = 0.01).await
      }

      enterBarrier("throttled_recv")

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

      enterBarrier("throttled_recv2")

      runOn(master) {
        testConductor.throttle(slave, master, Direction.Receive, -1).await
      }
    }

  }

}
