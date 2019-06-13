/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.Address
import akka.actor.Identify
import akka.actor.InternalActorRef
import akka.actor.Props
import akka.actor.RootActorPath
import akka.remote.RemoteWatcher.Stats
import akka.remote.RemoteWatcher.UnwatchRemote
import akka.remote.RemoteWatcher.WatchRemote
import akka.remote.artery.ArteryMultiNodeSpec
import akka.remote.artery.ArterySpecSupport
import akka.remote.artery.RemoteDeploymentSpec
import akka.testkit.EventFilter
import akka.testkit.ImplicitSender
import akka.testkit.SocketUtil
import akka.testkit.TestProbe
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object RemoteFeaturesSpec {

  val instances = 1
  val remoteSystemName = "remote"
  val staticRemoteAddress =
    Address("akka", remoteSystemName, Some("localhost"), Some(SocketUtil.temporaryServerAddress().getPort))

  // string config to pass into `ArteryMultiNodeSpec.extraConfig: Option[String]` for `other` system
  def common(useUnsafe: Boolean): String = s"""
       akka.remote.use-unsafe-remote-features-without-cluster = $useUnsafe
       akka.remote.artery.enabled = on
       akka.remote.artery.advanced.flight-recorder.enabled = off
       akka.log-dead-letters-during-shutdown = off
       """

  def disabled: Config =
    ConfigFactory.parseString(common(useUnsafe = false)).withFallback(ArterySpecSupport.defaultConfig)
  def enabled: Config = ConfigFactory.parseString(common(useUnsafe = true))

  class EmptyActor extends Actor {
    def receive: Receive = Actor.emptyBehavior
  }
}

abstract class RemoteFeaturesSpec(c: Config) extends ArteryMultiNodeSpec(c) with ImplicitSender {
  import RemoteFeaturesSpec._

  protected final val provider = RARP(system).provider

  protected final val useUnsafe: Boolean = provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster

  protected val remoteSystem = {
    // shutdown by super.afterTermination
    val config = s"""
       ${common(useUnsafe)}
       akka.remote.artery.canonical.hostname = "${staticRemoteAddress.host.get}"
       akka.remote.artery.canonical.port = ${staticRemoteAddress.port.get}
      """
    newRemoteSystem(name = Some(remoteSystemName), extraConfig = Some(config))
  }

  Seq(system, remoteSystem).foreach(
    muteDeadLetters(
      akka.remote.transport.AssociationHandle.Disassociated.getClass,
      akka.remote.transport.ActorTransportAdapter.DisassociateUnderlying.getClass)(_))

  import akka.remote.artery.RemoteWatcherSpec.TestRemoteWatcher
  protected val monitor = system.actorOf(Props(new TestRemoteWatcher), "monitor1")

  protected val watcher = system.actorOf(Props(new EmptyActor), "a1").asInstanceOf[InternalActorRef]

  protected val remoteWatchee = createRemoteActor(Props(new EmptyActor), "b1")

  protected def createRemoteActor(props: Props, name: String): InternalActorRef = {
    remoteSystem.actorOf(props, name)
    system.actorSelection(RootActorPath(address(remoteSystem)) / "user" / name) ! Identify(name)
    expectMsgType[ActorIdentity].ref.get.asInstanceOf[InternalActorRef]
  }
}

// all pre-existing remote tests exercise the rest of the unchanged enabled expectations
class RARPRemoteFeaturesEnabledSpec extends RemoteFeaturesSpec(RemoteFeaturesSpec.enabled) {
  "RARP without Cluster: opt-in unsafe enabled" must {

    "have the expected settings" in {
      provider.transport.system.settings.HasCluster shouldBe false
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe true
      provider.remoteSettings.WarnUnsafeWatchWithoutCluster shouldBe true
      provider.hasClusterOrUseUnsafe shouldBe true
    }

    "create a RemoteWatcher" in {
      provider.remoteWatcher.isDefined shouldBe true
    }
  }
}

// see the multi-jvm RemoteFeaturesSpec for deployer-router tests
class RemoteFeaturesDisabledSpec extends RemoteFeaturesSpec(RemoteFeaturesSpec.disabled) {

  private val port = RARP(system).provider.getDefaultAddress.port.get

  // super.newRemoteSystem adds the new system to shutdown hook
  private val masterSystem = newRemoteSystem(
    name = Some("Master" + system.name),
    extraConfig = Some(s"""
      akka.actor.deployment {
        /blub.remote = "akka://${system.name}@localhost:$port"
        "/parent*/*".remote = "akka://${system.name}@localhost:$port"
      }
    """))

  "Remote features without Cluster" must {

    "have the expected settings in a RARP" in {
      provider.transport.system.settings.HasCluster shouldBe false
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe false
      provider.remoteSettings.WarnUnsafeWatchWithoutCluster shouldBe true
      provider.hasClusterOrUseUnsafe shouldBe false
    }

    "not create a RemoteWatcher in a RARP" in {
      provider.remoteWatcher shouldEqual None
    }

    "not deathwatch a remote actor" in {
      EventFilter
        .warning(pattern = s"Dropped remote Watch: disabled for *", occurrences = 1)
        .intercept(monitor ! WatchRemote(remoteWatchee, watcher))
      monitor ! Stats
      expectMsg(Stats.empty)
      expectNoMessage(100.millis)

      EventFilter
        .warning(pattern = s"Dropped remote Unwatch: disabled for *", occurrences = 1)
        .intercept(monitor ! UnwatchRemote(remoteWatchee, watcher))

      monitor ! Stats
      expectMsg(Stats.empty)
      expectNoMessage(100.millis)
    }

    "fall back to creating local deploy children and supervise children on local node" in {
      val senderProbe = TestProbe()(masterSystem)
      val r = masterSystem.actorOf(Props[RemoteDeploymentSpec.Echo1], "blub")
      r.path.toString shouldEqual "akka://MasterRemoteFeaturesSpec/user/blub"

      r.tell(42, senderProbe.ref)
      senderProbe.expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }(masterSystem)
      senderProbe.expectMsg("preRestart")
      r.tell(43, senderProbe.ref)
      senderProbe.expectMsg(43)
      system.stop(r)
      senderProbe.expectMsg("postStop")
    }
  }
}
