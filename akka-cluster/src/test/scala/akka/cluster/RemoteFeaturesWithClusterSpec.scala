/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.Actor
import akka.actor.ExtendedActorSystem
import akka.remote.RemoteFeaturesSpec
import com.typesafe.config.ConfigFactory

object RemoteFeaturesWithClusterSpec {
  class TestActor extends Actor {
    def receive: Receive = { case _ => }
  }
}

class RemoteFeaturesWithClusterSpec
    extends RemoteFeaturesSpec(
      ConfigFactory.parseString("""akka.actor.provider = "cluster"""").withFallback(RemoteFeaturesSpec.disabled)) {

  "Remoting with Cluster" must {

    "have the correct settings" in {
      system.settings.HasCluster shouldBe true
      provider.hasClusterOrUseUnsafe shouldBe true
      provider.transport.system.settings.HasCluster shouldBe true
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe false
      system.settings.ProviderClass shouldEqual classOf[ClusterActorRefProvider].getName
    }

    "create a ClusterRemoteWatcher" in {
      provider.remoteWatcher.isDefined shouldBe true
    }

    "use ClusterDeployer" in {
      provider.deployer.getClass == classOf[ClusterDeployer] shouldBe true
    }

    "have expected `actorOf` behavior" in {
      val conf = s"""
        ${RemoteFeaturesSpec.common(false)}
        akka.actor.provider = "cluster"
        akka.remote.artery.canonical.port = 0"""

      val selfAddress = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
      val c1 = Cluster(system)
      c1.join(selfAddress)

      val joiningSystem = newRemoteSystem(name = Some(system.name), extraConfig = Some(conf))
      val c2 = Cluster(joiningSystem)
      c2.join(selfAddress)
      awaitCond(c1.state.members.size == 2)

      // WIP todo: next: if possible, test b and c ref type from actorOf, otherwise this test can be removed
      // val b = joiningSystem.actorOf(Props[RemoteFeaturesWithClusterSpec.TestActor])
      //val c = newRemoteSystem(name = Some("other"), extraConfig = Some(conf))
      // .actorOf(Props[RemoteFeaturesWithClusterSpec.TestActor])
    }
  }
}
