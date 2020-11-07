/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.remote.artery.ArterySettings
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter
import akka.testkit.LongRunningTest
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object SplitBrainQuarantineSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  testTransport(on = true)
  commonConfig(
    debugConfig(on = true)
      .withFallback(MultiNodeClusterSpec.clusterConfig)
      .withFallback(
        ConfigFactory.parseString("""
        akka.remote.artery.enabled = on
        akka.cluster.downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
        akka.cluster.split-brain-resolver.stable-after = 30s # we dont really want this to hit
        """)))
}

class SplitBrainQuarantineMultiJvmNode1 extends SplitBrainQuarantineSpec

class SplitBrainQuarantineMultiJvmNode2 extends SplitBrainQuarantineSpec

class SplitBrainQuarantineMultiJvmNode3 extends SplitBrainQuarantineSpec

class SplitBrainQuarantineMultiJvmNode4 extends SplitBrainQuarantineSpec

abstract class SplitBrainQuarantineSpec extends MultiNodeSpec(SplitBrainQuarantineSpec) with MultiNodeClusterSpec {
  import SplitBrainQuarantineSpec._

  "Cluster node downed by other" must {

    if (!ArterySettings(system.settings.config.getConfig("akka.remote.artery")).Enabled) {
      // this feature only works in Artery, because classic remoting will not accept connections from
      // a quarantined node, and that is too high risk of introducing regressions if changing that
      pending
    }

    "join cluster" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth)
      enterBarrier("after-1")
    }

    "split brain" taggedAs LongRunningTest in {
      runOn(first) {
        testConductor.blackhole(first, third, ThrottlerTransportAdapter.Direction.Both).await
        testConductor.blackhole(first, fourth, ThrottlerTransportAdapter.Direction.Both).await
        testConductor.blackhole(second, third, ThrottlerTransportAdapter.Direction.Both).await
        testConductor.blackhole(second, fourth, ThrottlerTransportAdapter.Direction.Both).await
      }
      enterBarrier("blackhole")

      within(15.seconds) {
        runOn(first) {
          cluster.down(third)
          cluster.down(fourth)
        }
        runOn(first, second) {
          awaitAssert {
            cluster.state.members.collect { case m if m.status == MemberStatus.Up => m.address } should ===(
              Set(address(first), address(second)))
          }
        }

        runOn(third) {
          cluster.down(first)
          cluster.down(second)
        }
        runOn(third, fourth) {
          awaitAssert {
            cluster.state.members.collect { case m if m.status == MemberStatus.Up => m.address } should ===(
              Set(address(third), address(fourth)))
          }
        }
      }
      enterBarrier("brain-split")
      system.log.info("Brain split, waiting 15s")
      Thread.sleep(15000)

      runOn(first) {
        system.log.info("unblackholing cluster")
        testConductor.passThrough(first, third, ThrottlerTransportAdapter.Direction.Both).await
        testConductor.passThrough(first, fourth, ThrottlerTransportAdapter.Direction.Both).await
        testConductor.passThrough(second, third, ThrottlerTransportAdapter.Direction.Both).await
        testConductor.passThrough(second, fourth, ThrottlerTransportAdapter.Direction.Both).await
      }
      // FIXME what happens now?
      Thread.sleep(30000)
      enterBarrier("pass-through")

    }

  }
}
