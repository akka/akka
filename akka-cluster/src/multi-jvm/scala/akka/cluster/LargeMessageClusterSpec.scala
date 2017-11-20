/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import scala.concurrent.duration._

import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.Identify
import akka.actor.PoisonPill
import akka.cluster.ClusterEvent.UnreachableMember
import akka.remote.RARP
import akka.remote.artery.ArterySettings
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object LargeMessageClusterMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  // Note that this test uses default configuration,
  // not MultiNodeClusterSpec.clusterConfig
  commonConfig(ConfigFactory.parseString(
    """
    akka {
      loglevel = DEBUG
      cluster.debug.verbose-heartbeat-logging = on
      loggers = ["akka.testkit.TestEventListener"]
      actor.provider = cluster

      testconductor.barrier-timeout = 3 minutes

      cluster.failure-detector.acceptable-heartbeat-pause = 5 s

      remote.artery {
        enabled = on

        large-message-destinations = [ "/user/largeEcho", "/system/largeEchoProbe-3" ]

        advanced {
          #maximum-frame-size = 2 MiB
          #buffer-pool-size = 32
          maximum-large-frame-size = 2 MiB
          large-buffer-pool-size = 32

          compression {
            #actor-refs.advertisement-interval = 2 second
            #manifests.advertisement-interval = 2 second
          }
        }
      }
    }
    """))

}

class LargeMessageClusterMultiJvmNode1 extends LargeMessageClusterSpec
class LargeMessageClusterMultiJvmNode2 extends LargeMessageClusterSpec
class LargeMessageClusterMultiJvmNode3 extends LargeMessageClusterSpec

abstract class LargeMessageClusterSpec extends MultiNodeSpec(LargeMessageClusterMultiJvmSpec)
  with MultiNodeClusterSpec with ImplicitSender {
  import LargeMessageClusterMultiJvmSpec._

  override def expectedTestDuration: FiniteDuration = 3.minutes

  def identify(role: RoleName, actorName: String): ActorRef = within(10.seconds) {
    system.actorSelection(node(role) / "user" / actorName) ! Identify(actorName)
    expectMsgType[ActorIdentity].ref.get
  }

  val unreachableProbe = TestProbe()

  "Artery Cluster with large messages" must {
    "init cluster" taggedAs LongRunningTest in {
      Cluster(system).subscribe(unreachableProbe.ref, ClusterEvent.InitialStateAsEvents,
        classOf[UnreachableMember])

      awaitClusterUp(first, second, third)

      // let heartbeat monitoring begin
      unreachableProbe.expectNoMessage(5.seconds)

      enterBarrier("init-done")
    }

    "not disturb cluster heartbeat messages when sent in bursts" taggedAs LongRunningTest in {

      runOn(second, third) {
        system.actorOf(TestActors.echoActorProps, "echo")
        system.actorOf(TestActors.echoActorProps, "largeEcho")
      }
      enterBarrier("actors-started")

      runOn(second) {
        val echo3 = identify(third, "echo")
        val largeEcho3 = identify(third, "largeEcho")
        val largeEchoProbe = TestProbe(name = "largeEchoProbe")

        val largeMsgSize = 2 * 1000 * 1000
        val largeMsg = ("0" * largeMsgSize).getBytes("utf-8")
        val largeMsgBurst = 3
        val repeat = 15
        for (n ← 1 to repeat) {
          val startTime = System.nanoTime()
          for (_ ← 1 to largeMsgBurst) {
            largeEcho3.tell(largeMsg, largeEchoProbe.ref)
          }

          val ordinaryProbe = TestProbe()
          echo3.tell(("0" * 1000).getBytes("utf-8"), ordinaryProbe.ref)
          ordinaryProbe.expectMsgType[Array[Byte]]
          val ordinaryDurationMs = (System.nanoTime() - startTime) / 1000 / 1000

          largeEchoProbe.receiveN(largeMsgBurst, 20.seconds)
          println(s"Burst $n took ${(System.nanoTime() - startTime) / 1000 / 1000} ms, ordinary $ordinaryDurationMs ms")
        }
      }
      enterBarrier("sending-complete-1")

      unreachableProbe.expectNoMessage(1.seconds)

      enterBarrier("after-1")
    }

    "not disturb cluster heartbeat messages when saturated" taggedAs LongRunningTest in {

      // FIXME only enabled for Aeron transport until #24576 is fixed
      val arterySettings = ArterySettings(system.settings.config.getConfig("akka.remote.artery"))
      if (!arterySettings.Enabled || arterySettings.Transport != ArterySettings.AeronUpd)
        pending

      runOn(second) {
        val largeEcho2 = identify(second, "largeEcho")
        val largeEcho3 = identify(third, "largeEcho")

        val largeMsgSize = 1 * 1000 * 1000
        val largeMsg = ("0" * largeMsgSize).getBytes("utf-8")
        (1 to 3).foreach { _ ⇒
          // this will ping-pong between second and third
          largeEcho2.tell(largeMsg, largeEcho3)
        }
      }

      unreachableProbe.expectNoMessage(10.seconds)

      enterBarrier("after-2")
    }
  }
}
