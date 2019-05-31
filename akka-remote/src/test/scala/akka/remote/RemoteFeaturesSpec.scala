/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.net.InetAddress

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ActorSystemImpl
import akka.actor.Deployer
import akka.actor.Nobody
import akka.actor.Props
import akka.dispatch.sysmsg.Supervise
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.testkit.SocketUtil
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object RemoteFeaturesSpec {

  val disabled: Config = ConfigFactory.parseString("""akka.actor.provider = "remote"""")

  // cluster with default
  val safe: Config =
    ConfigFactory.parseString("""akka.actor.provider = "cluster" """).withFallback(RemoteFeaturesSpec.disabled)

  // no cluster, with remote watcher & deployer enabled
  val unsafe: Config = ConfigFactory.parseString("""
      akka.actor.provider = "remote"
      akka.remote.use-unsafe-remote-features-without-cluster = "on"
      """).withFallback(disabled)

  class RemoteActor(listener: ActorRef) extends Actor {
    def receive: Actor.Receive = {
      case e => listener ! e
    }
  }
}

import RemoteFeaturesSpec.RemoteActor

abstract class RemoteFeaturesSpec(c: Config) extends AkkaSpec(c) with ImplicitSender {

  protected final val provider = RARP(system).provider
  provider.init(system.asInstanceOf[ActorSystemImpl])

  protected final val address = SocketUtil.temporaryServerAddress(InetAddress.getLocalHost.getHostAddress, udp = false)

  private val config = ConfigFactory.parseString(s"""
        akka.remote.artery.canonical.port = ${address.getPort}
        akka.remote.artery.bind.port = 0
      """)

  implicit final val remoteSystem = ActorSystem("sys", config.withFallback(ConfigFactory.load()))

  override def beforeTermination(): Unit = remoteSystem.terminate()

}

class DisableRemoteFeaturesWithoutClusterSpec extends RemoteFeaturesSpec(RemoteFeaturesSpec.disabled) {

  "Remoting without Cluster" must {

    "have the expected settings" in {
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe false
      provider.transport.system.settings.HasCluster shouldBe false
    }

    "not create a RemoteWatcher" in {
      provider.remoteWatcher shouldEqual None
    }

    "not send system messages to remote" in {
      val remote = remoteSystem.actorOf(Props(new RemoteActor(self)))
      val local = system.actorOf(Props(new RemoteActor(self)))
      val rar = new RemoteActorRef(
        provider.transport,
        provider.transport.localAddressForRemote(remote.path.address),
        remote.path,
        Nobody,
        props = None,
        deploy = None)

      rar.start()
      rar.isWatchIntercepted(watchee = remote, watcher = provider.remoteWatcher.get) shouldBe false
      rar.isWatchIntercepted(watchee = provider.remoteWatcher.get, watcher = remote) shouldBe false
      rar.isWatchIntercepted(watchee = local, watcher = remote) shouldBe false
      rar.isWatchIntercepted(watchee = remote, watcher = local) shouldBe false

      rar.suspend()
      expectNoMessage()
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

    "send system messages - RemoteActorRef is remote" in {
      val remote = remoteSystem.actorOf(Props(new RemoteActor(self)))
      val local = system.actorOf(Props(new RemoteActor(self)))
      val rar = new RemoteActorRef(
        provider.transport,
        provider.transport.localAddressForRemote(remote.path.address),
        remote.path,
        Nobody,
        props = None,
        deploy = None)

      rar.start()
      rar.isWatchIntercepted(watchee = remote, watcher = local) shouldBe true
      rar.isWatchIntercepted(watchee = remote, watcher = provider.remoteWatcher.get) shouldBe false
      rar.isWatchIntercepted(watchee = local, watcher = remote) shouldBe false
      rar.sendSystemMessage(Supervise(self, async = true))
      expectMsgType[Supervise].child shouldEqual self
      lastSender shouldEqual remote
    }

    "send system messages - RemoteActorRef is local" in {
      // TODO maybe we don't need this test
      val remote = remoteSystem.actorOf(Props(new RemoteActor(self)))
      val local = system.actorOf(Props(new RemoteActor(self)))
      val rar =
        new RemoteActorRef(provider.transport, local.path.address, local.path, Nobody, props = None, deploy = None)

      rar.start()
      rar.isWatchIntercepted(watchee = local, watcher = remote) shouldBe true
      rar.isWatchIntercepted(watchee = remote, watcher = local) shouldBe false
      rar.sendSystemMessage(Supervise(self, async = true))
      expectMsgType[Supervise].child shouldEqual self
      lastSender shouldEqual local
    }

    "use a RemoteDeployer" in {
      provider.deployer.getClass shouldEqual classOf[RemoteDeployer]
    }
  }
}

class EnableRemoteFeaturesWithClusterSpec extends RemoteFeaturesSpec(RemoteFeaturesSpec.safe) {

  "Remoting with Cluster" must {
    "have the correct settings" in {
      system.settings.HasCluster shouldBe true
      system.settings.ProviderClass shouldEqual "akka.cluster.ClusterActorRefProvider"
      provider.transport.system.settings.HasCluster shouldBe true
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe false
    }

    "create a RemoteWatcher" in {
      provider.remoteWatcher.isDefined shouldBe true
    }

    "should send system messages" in {
      val remote = remoteSystem.actorOf(Props(new RemoteActor(self)))
      val local = system.actorOf(Props(new RemoteActor(self)))
      val rar = new RemoteActorRef(
        provider.transport,
        provider.transport.localAddressForRemote(remote.path.address),
        remote.path,
        Nobody,
        props = None,
        deploy = None)

      rar.start()
      rar.isWatchIntercepted(watchee = remote, watcher = local) shouldBe true
      rar.isWatchIntercepted(watchee = remote, watcher = provider.remoteWatcher.get) shouldBe false
      rar.isWatchIntercepted(watchee = provider.remoteWatcher.get, watcher = remote) shouldBe false
      rar.isWatchIntercepted(watchee = local, watcher = remote) shouldBe false

      rar.sendSystemMessage(Supervise(self, async = true))
      expectMsgType[Supervise].child shouldEqual self
      lastSender shouldEqual remote
    }

    "use ClusterDeployer (when cluster is on cp, here it is not, so it should not be local Deployer)" in {
      // TODO test in cluster with ClusterDeployer on the cp: provider.deployer.getClass shouldEqual classOf[ClusterDeployer]
      provider.deployer.getClass != classOf[Deployer] shouldBe true
    }
  }
}
