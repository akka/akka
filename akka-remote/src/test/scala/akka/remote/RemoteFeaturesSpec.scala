/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Deployer
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object RemoteFeaturesSpec {

  val remote: Config =
    ConfigFactory.parseString("""
        akka.actor.provider = "remote"
        akka.remote.artery.canonical.port = 0
        akka.log-dead-letters-during-shutdown = off
        """)

  // no cluster, with remote watcher & deployer enabled
  val unsafe: Config = ConfigFactory.parseString("""
      akka.remote.use-unsafe-remote-features-without-cluster = "on"
      """).withFallback(remote)

  class RemoteActor(listener: ActorRef) extends Actor {
    def receive: Actor.Receive = {
      case e => listener ! e
    }
  }
}

abstract class RemoteFeaturesSpec(c: Config) extends AkkaSpec(c) with ImplicitSender {

  protected val provider = RARP(system).provider

}

class DisableRemoteFeaturesWithoutClusterSpec extends RemoteFeaturesSpec(RemoteFeaturesSpec.remote) {
  "Remoting without Cluster" must {

    "have the expected settings" in {
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe false
      provider.transport.system.settings.HasCluster shouldBe false
    }

    "not create a RemoteWatcher" in {
      provider.remoteWatcher shouldEqual None
    }

    "use a local Deployer" in {
      provider.deployer.getClass shouldEqual classOf[Deployer]
    }
  }
}

class EnableRemoteFeaturesWithoutClusterSpec extends RemoteFeaturesSpec(RemoteFeaturesSpec.unsafe) {
  "Remoting without Cluster optionally set to use unsafe remote features" must {

    "have the expected settings" in {
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe true
      provider.transport.system.settings.HasCluster shouldBe false
    }

    "create a RemoteWatcher" in {
      provider.remoteWatcher.isDefined shouldBe true
    }

    "use a RemoteDeployer" in {
      provider.deployer.getClass shouldEqual classOf[RemoteDeployer]
    }
  }
}
