/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.PoisonPill
import akka.actor.Props
import akka.event.EventStream
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.testkit._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object TransportFailConfig extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(s"""
      akka.loglevel = INFO
      akka.remote {
        transport-failure-detector {
          implementation-class = "akka.remote.TransportFailSpec$$TestFailureDetector"
          heartbeat-interval = 1 s
        }
        retry-gate-closed-for = 3 s
        # Don't trigger watch Terminated
        watch-failure-detector.acceptable-heartbeat-pause = 60 s
        #use-passive-connections = off
        # This test is not interesting for Artery, no transport failure detector
        # but it will not fail when running with Artery enabled
        artery.enabled = off
      }
      """)))

}

class TransportFailMultiJvmNode1 extends TransportFailSpec
class TransportFailMultiJvmNode2 extends TransportFailSpec

object TransportFailSpec {
  class Subject extends Actor {
    def receive = {
      case msg => sender() ! msg
    }
  }

  private val fdAvailable = new AtomicBoolean(true)

  // FD that will fail when `fdAvailable` flag is false
  class TestFailureDetector(config: Config, ev: EventStream) extends FailureDetector {
    @volatile private var active = false

    override def heartbeat(): Unit = {
      active = true
    }

    override def isAvailable: Boolean = {
      if (active) fdAvailable.get
      else true
    }

    override def isMonitoring: Boolean = active
  }
}

/**
 * This was a reproducer for issue #23010.
 * - watch from first to second node, i.e. sys msg with seq number 1
 * - trigger transport failure detection to tear down the connection
 * - the bug was that on the second node the ReliableDeliverySupervisor
 *   was stopped because the send buffer had not been used on that side,
 *   but that removed the receive buffer entry
 * - later, after gating elapsed another watch from first to second node,
 *   i.e. sys msg with seq number 2
 * - when that watch msg was received on the second node the receive buffer
 *   had been cleared and therefore it thought that seq number 1 was missing,
 *   and therefore sent nack to the first node
 * - when first node received the nack it thrown
 *   IllegalStateException: Error encountered while processing system message
 *     acknowledgement buffer: [2 {2}] ack: ACK[2, {1, 0}]
 *   caused by: ResendUnfulfillableException: Unable to fulfill resend request since
 *     negatively acknowledged payload is no longer in buffer
 *
 * This was fixed by not stopping the ReliableDeliverySupervisor so that the
 * receive buffer was preserved.
 */
abstract class TransportFailSpec extends RemotingMultiNodeSpec(TransportFailConfig) {
  import TransportFailConfig._
  import TransportFailSpec._

  override def initialParticipants = roles.size

  def identify(role: RoleName, actorName: String): ActorRef = {
    val p = TestProbe()
    (system.actorSelection(node(role) / "user" / actorName)).tell(Identify(actorName), p.ref)
    p.expectMsgType[ActorIdentity](remainingOrDefault).ref.get
  }

  "TransportFail" must {

    "reconnect" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        enterBarrier("actors-started")

        val subject = identify(second, "subject")
        watch(subject)
        subject ! "hello"
        expectMsg("hello")
      }

      runOn(second) {
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")
      }

      enterBarrier("watch-established")
      // trigger transport FD
      TransportFailSpec.fdAvailable.set(false)

      // wait for ungated (also later awaitAssert retry)
      Thread.sleep(RARP(system).provider.remoteSettings.RetryGateClosedFor.toMillis)
      TransportFailSpec.fdAvailable.set(true)

      runOn(first) {
        enterBarrier("actors-started2")
        val quarantineProbe = TestProbe()
        system.eventStream.subscribe(quarantineProbe.ref, classOf[QuarantinedEvent])

        var subject2: ActorRef = null
        awaitAssert({
          within(1.second) {
            subject2 = identify(second, "subject2")
          }
        }, max = 5.seconds)
        watch(subject2)
        quarantineProbe.expectNoMsg(1.seconds)
        subject2 ! "hello2"
        expectMsg("hello2")
        enterBarrier("watch-established2")
        expectTerminated(subject2)
      }

      runOn(second) {
        val subject2 = system.actorOf(Props[Subject], "subject2")
        enterBarrier("actors-started2")
        enterBarrier("watch-established2")
        subject2 ! PoisonPill
      }

      enterBarrier("done")

    }

  }
}
