/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.AddressFromURIString
import akka.actor.Props
import akka.actor.RepointableActorRef
import akka.cluster.ClusterRemoteFeatures.AddressPing
import akka.remote.RARP
import akka.remote.RemoteActorRef
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteWatcher.Heartbeat
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

class ClusterRemoteFeaturesConfig(artery: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  private val baseConfig = ConfigFactory.parseString(s"""
      akka.remote.log-remote-lifecycle-events = off
      akka.remote.artery.enabled = $artery
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.advanced.flight-recorder.enabled = off
      akka.log-dead-letters-during-shutdown = off
      """).withFallback(MultiNodeClusterSpec.clusterConfig)

  commonConfig(debugConfig(on = false).withFallback(baseConfig))

  deployOn(first, """/kattdjur.remote = "@second@" """)
  deployOn(third, """/kattdjur.remote = "@second@" """)
  deployOn(second, """/kattdjur.remote = "@third@" """)

}

object ClusterRemoteFeatures {
  class AddressPing extends Actor {
    def receive: Receive = {
      case "ping" => sender() ! self
    }
  }
}

class ArteryClusterRemoteFeaturesMultiJvmNode1 extends ClusterRemoteFeaturesSpec(new ClusterRemoteFeaturesConfig(true))
class ArteryClusterRemoteFeaturesMultiJvmNode2 extends ClusterRemoteFeaturesSpec(new ClusterRemoteFeaturesConfig(true))
class ArteryClusterRemoteFeaturesMultiJvmNode3 extends ClusterRemoteFeaturesSpec(new ClusterRemoteFeaturesConfig(true))

class ClassicClusterRemoteFeaturesMultiJvmNode1
    extends ClusterRemoteFeaturesSpec(new ClusterRemoteFeaturesConfig(false))
class ClassicClusterRemoteFeaturesMultiJvmNode2
    extends ClusterRemoteFeaturesSpec(new ClusterRemoteFeaturesConfig(false))
class ClassicClusterRemoteFeaturesMultiJvmNode3
    extends ClusterRemoteFeaturesSpec(new ClusterRemoteFeaturesConfig(false))

abstract class ClusterRemoteFeaturesSpec(multiNodeConfig: ClusterRemoteFeaturesConfig)
    extends MultiNodeSpec(multiNodeConfig)
    with MultiNodeClusterSpec
    with ImplicitSender
    with ScalaFutures {

  import multiNodeConfig._

  override def initialParticipants: Int = roles.size

  muteDeadLetters(Heartbeat.getClass)()

  protected val provider: RemoteActorRefProvider = RARP(system).provider

  "Remoting with Cluster" must {

    "have the correct settings" in {
      runOn(first) {
        system.settings.HasCluster shouldBe true
        provider.hasClusterOrUseUnsafe shouldBe true
        provider.transport.system.settings.HasCluster shouldBe true
        provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe false
        system.settings.ProviderClass shouldEqual classOf[ClusterActorRefProvider].getName
      }
    }

    "create a ClusterRemoteWatcher" in {
      runOn(roles: _*)(provider.remoteWatcher.isDefined shouldBe true)
    }

    "create a ClusterDeployer" in {
      runOn(roles: _*)(provider.deployer.getClass shouldEqual classOf[ClusterDeployer])
    }

    "have expected `actorOf` behavior" in {
      awaitClusterUp(first, second)
      enterBarrier("cluster-up")

      runOn(first) {
        val actor = system.actorOf(Props[AddressPing], "kattdjur")
        actor.isInstanceOf[RemoteActorRef] shouldBe true
        actor.path.address shouldEqual node(second).address
        actor.path.address.hasGlobalScope shouldBe true

        val secondAddress = node(second).address
        actor ! "ping"
        expectMsgType[RemoteActorRef].path.address shouldEqual secondAddress
      }
      enterBarrier("CARP-in-cluster-remote-validated")

      def assertIsLocalRef(): Unit = {
        val actor = system.actorOf(Props[AddressPing], "kattdjur")
        actor.isInstanceOf[RepointableActorRef] shouldBe true
        val localAddress = AddressFromURIString(s"akka://${system.name}")
        actor.path.address shouldEqual localAddress
        actor.path.address.hasLocalScope shouldBe true

        actor ! "ping"
        expectMsgType[ActorRef].path.address shouldEqual localAddress
      }

      runOn(third) {
        Cluster(system).state.isMemberUp(node(third).address) shouldBe false
        assertIsLocalRef()
      }
      enterBarrier("CARP-outside-cluster-local-validated")

      runOn(second) {
        assertIsLocalRef()
      }
      enterBarrier("CARP-inside-cluster-to-non-member-local-validated")
    }
  }
}
