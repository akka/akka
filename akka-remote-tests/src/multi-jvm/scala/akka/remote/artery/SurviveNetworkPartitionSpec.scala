/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import akka.actor._
import akka.actor.ActorIdentity
import akka.actor.Identify
import akka.remote.RARP
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.remote.QuarantinedEvent

object SurviveNetworkPartitionSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = DEBUG
      akka.remote.artery.enabled = on
      akka.remote.artery.advanced.give-up-system-message-after = 30s
      akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 45s
      akka.testconductor.barrier-timeout = 60s
      """)))

  nodeConfig(first)(ConfigFactory.parseString("""
      akka.remote.artery.canonical.port = 25521
      """))

  nodeConfig(second)(ConfigFactory.parseString("""
      akka.remote.artery.canonical.port = 25522
      """))

  testTransport(on = true)
}

class SurviveNetworkPartitionSpecMultiJvmNode1 extends SurviveNetworkPartitionSpec
class SurviveNetworkPartitionSpecMultiJvmNode2 extends SurviveNetworkPartitionSpec

abstract class SurviveNetworkPartitionSpec
  extends MultiNodeSpec(SurviveNetworkPartitionSpec)
  with STMultiNodeSpec with ImplicitSender {

  import SurviveNetworkPartitionSpec._

  override def initialParticipants = roles.size

  "Network partition" must {

    "not quarantine system when it heals within 'give-up-system-message-after'" taggedAs LongRunningTest in {

      runOn(second) {
        system.actorOf(TestActors.echoActorProps, "echo1")
      }
      enterBarrier("echo-started")

      runOn(first) {
        system.actorSelection(node(second) / "user" / "echo1") ! Identify(None)
        val ref = expectMsgType[ActorIdentity].ref.get
        ref ! "ping1"
        expectMsg("ping1")

        // network partition
        //        testConductor.blackhole(first, second, Direction.Both).await
        println(s"# start blackhole") // FIXME
        Thread.sleep(10000)
        println(s"# keep blackhole") // FIXME

        // send system message during network partition
        watch(ref)
        // keep the network partition for a while, but shorter than give-up-system-message-after
        ref ! "ping2a"
        expectNoMsg(2.second)

        Thread.sleep(10000)
        println(s"# heal blackhole") // FIXME

        // heal the network partition
        //        testConductor.passThrough(first, second, Direction.Both).await

        Thread.sleep(10000)
        //        expectMsg("ping2a")

        println(s"# sending ping2") // FIXME
        // not quarantined
        ref ! "ping2"
        expectMsg("ping2")

        ref ! PoisonPill
        expectTerminated(ref)
      }

      enterBarrier("done")
    }

    "quarantine system when it doesn't heal within 'give-up-system-message-after'" taggedAs LongRunningTest ignore {

      runOn(second) {
        system.actorOf(TestActors.echoActorProps, "echo2")
      }
      enterBarrier("echo-started")

      runOn(first) {
        val qProbe = TestProbe()
        system.eventStream.subscribe(qProbe.ref, classOf[QuarantinedEvent])
        system.actorSelection(node(second) / "user" / "echo2") ! Identify(None)
        val ref = expectMsgType[ActorIdentity].ref.get
        ref ! "ping1"
        expectMsg("ping1")

        // network partition
        testConductor.blackhole(first, second, Direction.Both).await

        // send system message during network partition
        watch(ref)
        // keep the network partition for a while, longer than give-up-system-message-after
        expectNoMsg(RARP(system).provider.remoteSettings.Artery.Advanced.GiveUpSystemMessageAfter - 1.second)
        qProbe.expectMsgType[QuarantinedEvent](5.seconds).address should ===(node(second).address)

        expectTerminated(ref)
      }

      enterBarrier("done")
    }

  }
}
