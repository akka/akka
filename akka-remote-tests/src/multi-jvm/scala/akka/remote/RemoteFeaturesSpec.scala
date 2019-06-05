/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.actor.Nobody
import akka.actor.Props
import akka.remote.RemoteWatcher.Stats
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.testkit.EventFilter
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

class RemotingFeaturesConfig(val useUnsafe: Boolean, artery: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  protected val baseConfig = ConfigFactory.parseString(s"""
      akka.remote.use-unsafe-remote-features-without-cluster = $useUnsafe
      akka.remote.log-remote-lifecycle-events = off
      akka.remote.artery.enabled = $artery
      akka.remote.artery.advanced.flight-recorder.enabled = off
      """).withFallback(RemotingMultiNodeSpec.commonConfig)

  commonConfig(debugConfig(on = false).withFallback(baseConfig))

  def remoteRole(myself: RoleName): RoleName = if (myself == first) second else first

}

class RemotingFeaturesSafeMultiJvmNode1 extends RemotingFeaturesSafeSpec
class RemotingFeaturesSafeMultiJvmNode2 extends RemotingFeaturesSafeSpec

class RemotingFeaturesUnsafeMultiJvmNode1 extends RemotingFeaturesUnsafeSpec
class RemotingFeaturesUnsafeMultiJvmNode2 extends RemotingFeaturesUnsafeSpec

abstract class RemotingFeaturesSafeSpec
    extends RemotingFeaturesSpec(new RemotingFeaturesConfig(useUnsafe = false, artery = true)) {

  import RemoteNodeDeathWatchSpec.Ack
  import RemoteNodeDeathWatchSpec.ProbeActor
  import RemoteNodeDeathWatchSpec.WatchIt
  import multiNodeConfig._

  "Remoting without Cluster" must {

    "not use 'remoteWatcher' and use a local deployer in `RemoteActorRefProvider`" in {
      provider.settings.HasCluster shouldBe false
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe false
      provider.remoteWatcher.isEmpty shouldBe true
    }

    "not intercept and send system messages `Watch`/`Unwatch` to `RemoteWatcher` in the provider" in {
      runOn(second) {
        val watchee = system.actorOf(Props(new ProbeActor(probe.ref)), "watchee")
        enterBarrier("started")
        assertWatchNotIntercepted(identify(first, "watcher"), watchee)
      }
      runOn(first) {
        val watcher = system.actorOf(Props(new ProbeActor(probe.ref)), "watcher")
        enterBarrier("started")
        assertWatchNotIntercepted(watcher, identify(second, "watchee"))
      }

      def assertWatchNotIntercepted(watcher: ActorRef, watchee: ActorRef): Unit = {
        val rar = remoteActorRef(remoteRole)
        rar.isWatchIntercepted(watchee = rar, watcher = watcher) shouldBe false
        rar.isWatchIntercepted(watchee = watchee, watcher = watcher) shouldBe false
        enterBarrier("watch-not-intercepted")
      }
    }

    "not `Watch` and `Unwatch` from `RemoteWatcher`" in {
      runOn(first) {
        val watcher = identify(first, "watcher")
        val watchee = identify(second, "watchee")

        EventFilter
          .warning(pattern = "Dropped remote Watch/Unwatch: remote watch disabled for*", occurrences = 1)
          .intercept(probe.send(watcher, WatchIt(watchee)))
        probe.expectMsg(1.second, Ack)
        enterBarrier("system-message-not-sent")
      }
      runOn(second) {
        enterBarrier("system-message-not-sent")
      }
    }
  }
}

abstract class RemotingFeaturesUnsafeSpec
    extends RemotingFeaturesSpec(new RemotingFeaturesConfig(useUnsafe = true, artery = true)) {

  import RemoteNodeDeathWatchSpec.Ack
  import RemoteNodeDeathWatchSpec.DeathWatchIt
  import RemoteNodeDeathWatchSpec.ProbeActor
  import RemoteNodeDeathWatchSpec.UnwatchIt
  import RemoteNodeDeathWatchSpec.WatchIt
  import multiNodeConfig._

  def stats(watcher: ActorRef, message: DeathWatchIt): Stats = {
    probe.send(watcher, message)
    probe.expectMsg(1.second, Ack)
    provider.remoteWatcher.get ! Stats
    expectMsgType[Stats]
  }

  "Remoting with UseUnsafeRemoteFeaturesWithoutCluster enabled" must {

    "create 'remoteWatcher' in `RemoteActorRefProvider`" in {
      provider.settings.HasCluster shouldBe false
      provider.remoteSettings.UseUnsafeRemoteFeaturesWithoutCluster shouldBe true
      provider.remoteWatcher.isDefined shouldBe true
      provider.deployer.getClass shouldEqual classOf[RemoteDeployer]
    }

    "intercept and send system messages `Watch`/`Unwatch` to `RemoteWatcher` in the provider" in {
      runOn(second) {
        val watchee = system.actorOf(Props(new ProbeActor(probe.ref)), "watchee")
        enterBarrier("watchee-started")
        enterBarrier("watcher-started")
        assertWatchIntercepted(identify(first, "watcher"), watchee)
      }
      runOn(first) {
        enterBarrier("watchee-started")
        val watcher = system.actorOf(Props(new ProbeActor(probe.ref)), "watcher")
        enterBarrier("watcher-started")
        assertWatchIntercepted(watcher, identify(second, "watchee"))
      }

      def assertWatchIntercepted(watcher: ActorRef, watchee: ActorRef): Unit = {
        val remoteWatcher = identifyWithPath(remoteRole, "system", "remote-watcher")
        val rar = remoteActorRef(remoteRole)
        rar.isWatchIntercepted(watchee = rar, watcher = watcher) shouldBe true
        rar.isWatchIntercepted(watchee = rar, watcher = remoteWatcher) shouldBe true
        enterBarrier("watch-intercepted")

        rar.isWatchIntercepted(watchee = watchee, watcher = rar) shouldBe false
        enterBarrier("watch-not-intercepted")
      }
    }

    "`Watch` from `RemoteWatcher`" in {
      runOn(first) {
        val watcher = identify(first, "watcher")
        val watchee = identify(second, "watchee")
        awaitCond(stats(watcher, WatchIt(watchee)).watchingRefs == Set((watchee, watcher)), 2.seconds)
        enterBarrier("system-message1-received-by-remoteWatcher")
      }
      runOn(second) {
        enterBarrier("system-message1-received-by-remoteWatcher")
      }
    }

    "`Unwatch` from `RemoteWatcher`" in {
      runOn(first) {
        val watchee = identify(second, "watchee")
        awaitCond(stats(identify(first, "watcher"), UnwatchIt(watchee)).watching == 0, 2.seconds)
        enterBarrier("system-message2-received-by-remoteWatcher")
      }
      runOn(second) {
        enterBarrier("system-message2-received-by-remoteWatcher")
      }
    }
  }
}

abstract class RemotingFeaturesSpec(val multiNodeConfig: RemotingFeaturesConfig)
    extends RemotingMultiNodeSpec(multiNodeConfig) {

  import RemoteWatcher._

  override def initialParticipants: Int = roles.size

  muteDeadLetters(Heartbeat.getClass)()

  protected val probe = TestProbe()

  lazy val provider: RemoteActorRefProvider = RARP(system).provider

  val remoteRole: RoleName = multiNodeConfig.remoteRole(myself)

  def remoteActorRef(role: RoleName): RemoteActorRef = {
    val remotePath = node(role)
    val rar = new RemoteActorRef(
      provider.transport,
      provider.transport.localAddressForRemote(remotePath.address),
      remotePath,
      Nobody,
      None,
      None)

    rar.start()
    rar
  }
}
