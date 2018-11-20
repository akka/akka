/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.duration._

import akka.remote.OtherHasQuarantinedThisActorSystemEvent
import akka.remote.artery.ArterySettings
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter
import akka.testkit.LongRunningTest
import com.typesafe.config.ConfigFactory

object DowningWhenOtherHasQuarantinedThisActorSystemSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(MultiNodeClusterSpec.clusterConfig)
    .withFallback(ConfigFactory.parseString("""
        akka.remote.artery.enabled = on
        """)))

  nodeConfig(first, third) {
    ConfigFactory.parseString("akka.cluster.auto-down-unreachable-after = 2s")
  }

  testTransport(on = true)
}

class DowningWhenOtherHasQuarantinedThisActorSystemMultiJvmNode1 extends DowningWhenOtherHasQuarantinedThisActorSystemSpec
class DowningWhenOtherHasQuarantinedThisActorSystemMultiJvmNode2 extends DowningWhenOtherHasQuarantinedThisActorSystemSpec
class DowningWhenOtherHasQuarantinedThisActorSystemMultiJvmNode3 extends DowningWhenOtherHasQuarantinedThisActorSystemSpec

abstract class DowningWhenOtherHasQuarantinedThisActorSystemSpec extends MultiNodeSpec(DowningWhenOtherHasQuarantinedThisActorSystemSpec)
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

      within(10.seconds) {
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
        // shutting down itself
        awaitCond(cluster.isTerminated)
      }

      enterBarrier("after-2")
    }

    "FIXME illustrate trouble" taggedAs LongRunningTest in {
      runOn(first) {
        system.eventStream.subscribe(testActor, classOf[OtherHasQuarantinedThisActorSystemEvent])
      }
      enterBarrier("subscribing")

      runOn(third) {
        // The ActorSystem continues running and will reply with Quarantined when receiving heartbeats from first,
        // which result in that first is downing itself, which is undesired.
        // Normally the ActorSystem will be terminated (or exit JVM) but there could be race conditions during
        // shutdown that would result in same issue.
        cluster.shutdown()
      }

      runOn(first) {
        // FIXME this is failing, OtherHasQuarantinedThisActorSystemEvent is published
        expectNoMessage(10.seconds)
      }

      enterBarrier("after-2")
    }

  }
}
