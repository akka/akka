/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.remote.transport.AssociationHandle
import akka.remote.transport.ThrottlerTransportAdapter.{ ForceDisassociateExplicitly, Direction }

import language.postfixOps
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.testkit._
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.remote.testconductor.RoleName

object QuarantineRemainIntactSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      #akka.loglevel = INFO
      #akka.remote.log-remote-lifecycle-events = DEBUG
      akka.remote.retry-gate-closed-for = 0.5s
      akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 3s
      akka.remote.transport-failure-detector.acceptable-heartbeat-pause = 5s
                              """)))

  testTransport(on = true)

  class Subject extends Actor {
    def receive = {
      case "ping" ⇒ sender() ! "pong"
    }
  }

}

class QuarantineRemainIntactSpecMultiJvmNode1 extends QuarantineRemainIntactSpec
class QuarantineRemainIntactSpecMultiJvmNode2 extends QuarantineRemainIntactSpec

abstract class QuarantineRemainIntactSpec extends MultiNodeSpec(QuarantineRemainIntactSpec)
  with STMultiNodeSpec
  with ImplicitSender {

  import QuarantineRemainIntactSpec._

  override def initialParticipants = roles.size

  "While probing through the quarantine remoting" must {

    "not lose existing quarantine marker" taggedAs LongRunningTest in {
      runOn(first) {
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")

        // Communicate with second system
        val ref = Await.result(
          system.actorSelection(node(second) / "user" / "subject").resolveOne(3.seconds),
          3.seconds)
        ref ! "ping"
        expectMsg("pong")

        enterBarrier("actor-identified-1")
        enterBarrier("actor-identified-2")
        watch(ref)

        // Give time for watch to get through
        Thread.sleep(1000)

        testConductor.blackhole(first, second, Direction.Receive).await

        within(10.seconds) {
          awaitAssert {
            EventFilter.warning(pattern = "UID is now quarantined", occurrences = 1).intercept {
              ref ! "ping!"
            }
          }
        }
        expectTerminated(ref)
        testConductor.passThrough(first, second, Direction.Send).await
        println("wait for Q")

        enterBarrier("quarantine-up")

        for (_ ← 1 to 10) {
          println("A pinging")
          ref ! "ping"
          expectNoMsg(2.second)
        }

        enterBarrier("quarantine-intact")

      }

      runOn(second) {
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")
        enterBarrier("actor-identified-1")

        val ref = Await.result(
          system.actorSelection(node(first) / "user" / "subject").resolveOne(3.seconds),
          3.seconds)
        ref ! "ping"
        expectMsg("pong")

        enterBarrier("actor-identified-2")
        enterBarrier("quarantine-up")

        for (_ ← 1 to 10) {
          println("B pinging")
          ref ! "ping"
          expectNoMsg(2.second)
        }

        enterBarrier("quarantine-intact")
      }

    }

  }
}
