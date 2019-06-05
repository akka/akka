/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.actor.InternalActorRef
import akka.actor.Nobody
import akka.actor.Props
import akka.dispatch.sysmsg.Watch
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
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

    "not create 'remoteWatcher' in `RemoteActorRefProvider`" in {
      provider.remoteWatcher.isEmpty shouldBe true
    }

    "not intercept system `Watch`/`Unwatch` for `remoteWatcher`" in {
      runOn(second) {
        system.actorOf(Props(new ProbeActor(testActor)), "watchee1")
        enterBarrier("started-watchee1")
      }

      runOn(first) {
        val watcher = system.actorOf(Props(new ProbeActor(testActor)), "watcher1")
        enterBarrier("started-watchee1")

        val watchee = identify(second, "watchee1")
        watcher ! WatchIt(watchee) // will never get sent to internal destination
        expectMsg(1.second, Ack) // just for show that context.watch(remote) is not happening
        watchee ! "hello1"
        enterBarrier("received-hello1")

        assertWatchNotIntercepted(watcher, watchee)
        enterBarrier("watchee1-stopped")
        expectNoMessage(2.seconds)
        info(s"$myself did not receive Terminated")
        enterBarrier("watch-not-established")
      }
      runOn(second) {
        expectMsg(5.seconds, "hello1")
        enterBarrier("received-hello1")

        val watchee = identify(second, "watchee1")
        assertWatchNotIntercepted(identify(first, "watcher1"), watchee)
        info(s"$myself stopping watchee.")
        system.stop(watchee)
        enterBarrier("watchee1-stopped")
        enterBarrier("watch-not-established")
      }

      def assertWatchNotIntercepted(watcher: ActorRef, watchee: ActorRef): Unit = {
        val rar = remoteActorRef(remoteRole)
        rar.isWatchIntercepted(watchee = rar, watcher = watcher) shouldBe false
        rar.isWatchIntercepted(watchee = watchee, watcher = watcher) shouldBe false
        enterBarrier("watch-not-intercepted")
      }
    }

    "not send remote system messages" in {
      runOn(second) {
        system.actorOf(Props(new ProbeActor(testActor)), "watchee2")
        enterBarrier("started-watchee2")
        enterBarrier("finished")
      }
      runOn(first) {
        val watcher = identify(first, "watcher1").asInstanceOf[InternalActorRef]
        val watchee = identify(second, "watchee2").asInstanceOf[InternalActorRef]
        enterBarrier("started-watchee2")

        watcher ! WatchIt(watchee) // will never get sent to internal destination
        expectMsg(1.second, Ack) // meaningless

        val rar = remoteActorRef(second)
        rar.sendSystemMessage(Watch(watchee, watcher))
        expectNoMessage()

        rar.suspend()
        expectNoMessage()
        enterBarrier("finished")
      }
    }
  }
}

abstract class RemotingFeaturesUnsafeSpec
    extends RemotingFeaturesSpec(new RemotingFeaturesConfig(useUnsafe = true, artery = true)) {

  import RemoteNodeDeathWatchSpec.Ack
  import RemoteNodeDeathWatchSpec.ProbeActor
  import RemoteNodeDeathWatchSpec.WatchIt
  import RemoteNodeDeathWatchSpec.WrappedTerminated
  import multiNodeConfig._

  "Remoting without Cluster" must {

    "create 'remoteWatcher' in `RemoteActorRefProvider`" in {
      provider.remoteWatcher.isDefined shouldBe true
    }

    "intercept system `Watch`/`Unwatch` for `remoteWatcher`" in {
      runOn(second) {
        system.actorOf(Props(new ProbeActor(testActor)), "watchee1")
        enterBarrier("started-watchee1")
      }

      runOn(first) {
        val watcher = system.actorOf(Props(new ProbeActor(testActor)), "watcher1")
        enterBarrier("started-watchee1")

        val watchee = identify(second, "watchee1")
        watcher ! WatchIt(watchee)
        expectMsg(1.second, Ack)
        watchee ! "hello1"
        enterBarrier("received-hello1")

        assertWatchIntercepted(watcher, watchee)
        enterBarrier("watchee1-stopped")
        expectMsgType[WrappedTerminated].t.actor shouldEqual watchee
        enterBarrier("watch-established")
      }
      runOn(second) {
        expectMsg(5.seconds, "hello1")
        enterBarrier("received-hello1")

        val watchee = identify(second, "watchee1")
        assertWatchIntercepted(identify(first, "watcher1"), watchee)
        system.stop(watchee)
        enterBarrier("watchee1-stopped")
        enterBarrier("watch-established")
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

    "send remote system messages" in {
      // TODO test
    }
  }
}

abstract class RemotingFeaturesSpec(val multiNodeConfig: RemotingFeaturesConfig)
    extends RemotingMultiNodeSpec(multiNodeConfig) {

  import RemoteWatcher._

  override def initialParticipants: Int = roles.size

  muteDeadLetters(Heartbeat.getClass)()

  val provider: RemoteActorRefProvider = RARP(system).provider

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
