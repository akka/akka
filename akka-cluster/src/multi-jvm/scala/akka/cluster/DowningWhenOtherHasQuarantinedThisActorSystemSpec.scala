/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.RootActorPath

import scala.concurrent.duration._
import akka.remote.artery.ArterySettings
import akka.remote.artery.ThisActorSystemQuarantinedEvent
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter
import akka.testkit.LongRunningTest
import com.typesafe.config.ConfigFactory

object DowningWhenOtherHasQuarantinedThisActorSystemSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(MultiNodeClusterSpec.clusterConfig)
      .withFallback(
        ConfigFactory.parseString("""
        akka.remote.artery.enabled = on
        akka.cluster.downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
        # speed up decision
        akka.cluster.split-brain-resolver.stable-after = 5s
        """)))

  // exaggerate the timing issue by ,making the second node decide slower
  // this is to more consistently repeat the scenario where the other side completes downing
  // while the isolated part still has not made a decision and then see quarantined connections from the other nodes
  nodeConfig(second)(ConfigFactory.parseString("akka.cluster.split-brain-resolver.stable-after = 15s"))

  testTransport(on = true)
}

class DowningWhenOtherHasQuarantinedThisActorSystemMultiJvmNode1
    extends DowningWhenOtherHasQuarantinedThisActorSystemSpec
class DowningWhenOtherHasQuarantinedThisActorSystemMultiJvmNode2
    extends DowningWhenOtherHasQuarantinedThisActorSystemSpec
class DowningWhenOtherHasQuarantinedThisActorSystemMultiJvmNode3
    extends DowningWhenOtherHasQuarantinedThisActorSystemSpec

abstract class DowningWhenOtherHasQuarantinedThisActorSystemSpec
    extends MultiNodeSpec(DowningWhenOtherHasQuarantinedThisActorSystemSpec)
    with MultiNodeClusterSpec {
  import DowningWhenOtherHasQuarantinedThisActorSystemSpec._

  "Cluster node downed by other" must {

    if (!ArterySettings(system.settings.config.getConfig("akka.remote.artery")).Enabled) {
      // this feature only works in Artery, because classic remoting will not accept connections from
      // a quarantined node, and that is too high risk of introducing regressions if changing that
      pending
    }

    "join cluster" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)
      enterBarrier("after-1")
    }

    "down itself" taggedAs LongRunningTest in {
      runOn(first) {
        testConductor.blackhole(first, second, ThrottlerTransportAdapter.Direction.Both).await
        testConductor.blackhole(third, second, ThrottlerTransportAdapter.Direction.Both).await
      }
      enterBarrier("blackhole")

      within(15.seconds) {
        runOn(first) {
          awaitAssert {
            cluster.state.unreachable.map(_.address) should ===(Set(address(second)))
          }
          awaitAssert {
            // second downed and removed
            cluster.state.members.map(_.address) should ===(Set(address(first), address(third)))
          }
        }
        runOn(second) {
          awaitAssert {
            cluster.state.unreachable.map(_.address) should ===(Set(address(first), address(third)))
          }
        }
      }
      enterBarrier("down-second")

      runOn(first) {
        testConductor.passThrough(first, second, ThrottlerTransportAdapter.Direction.Both).await
        testConductor.passThrough(third, second, ThrottlerTransportAdapter.Direction.Both).await
      }
      enterBarrier("pass-through")

      runOn(second) {
        within(10.seconds) {
          awaitAssert {
            // try to ping first (Cluster Heartbeat messages will not trigger the Quarantine message)
            system.actorSelection(RootActorPath(first) / "user").tell(Identify(None), ActorRef.noSender)
            // shutting down itself triggered by ThisActorSystemQuarantinedEvent
            cluster.isTerminated should ===(true)
          }
        }
      }

      enterBarrier("after-2")
    }

    "not be triggered by another node shutting down" taggedAs LongRunningTest in {
      runOn(first) {
        system.eventStream.subscribe(testActor, classOf[ThisActorSystemQuarantinedEvent])
      }
      enterBarrier("subscribing")

      runOn(third) {
        cluster.shutdown()
      }

      runOn(first) {
        val sel = system.actorSelection(RootActorPath(third) / "user")
        (1 to 25).foreach { _ =>
          sel.tell(Identify(None), ActorRef.noSender) // try to ping third
          expectNoMessage(200.millis) // no ThisActorSystemQuarantinedEvent
        }
      }

      enterBarrier("after-2")
    }

  }
}
