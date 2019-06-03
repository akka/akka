/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.remote.RemoteFeaturesSpec
import com.typesafe.config.ConfigFactory

class EnableRemoteFeaturesWithClusterSpec
    extends RemoteFeaturesSpec(
      ConfigFactory.parseString("""akka.actor.provider = "cluster" """).withFallback(RemoteFeaturesSpec.remote)) {

  "Remoting with Cluster" must {

    "have the correct settings" in {
      system.settings.HasCluster shouldBe true
      system.settings.ProviderClass shouldEqual "akka.cluster.ClusterActorRefProvider"
      provider.transport.system.settings.HasCluster shouldBe true
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe false
    }

    "create a ClusterRemoteWatcher" in {
      provider.remoteWatcher.isDefined shouldBe true
    }

    "use ClusterDeployer" in {
      provider.deployer.getClass == classOf[ClusterDeployer] shouldBe true
    }
  }
}
