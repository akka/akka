/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._
import akka.actor.Address
import akka.remote.testconductor.Direction

object SplitBrainMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
        akka.cluster {
          auto-down = on
          failure-detector.threshold = 4
        }""")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class SplitBrainWithFailureDetectorPuppetMultiJvmNode1 extends SplitBrainSpec with FailureDetectorPuppetStrategy
class SplitBrainWithFailureDetectorPuppetMultiJvmNode2 extends SplitBrainSpec with FailureDetectorPuppetStrategy
class SplitBrainWithFailureDetectorPuppetMultiJvmNode3 extends SplitBrainSpec with FailureDetectorPuppetStrategy
class SplitBrainWithFailureDetectorPuppetMultiJvmNode4 extends SplitBrainSpec with FailureDetectorPuppetStrategy
class SplitBrainWithFailureDetectorPuppetMultiJvmNode5 extends SplitBrainSpec with FailureDetectorPuppetStrategy

class SplitBrainWithAccrualFailureDetectorMultiJvmNode1 extends SplitBrainSpec with AccrualFailureDetectorStrategy
class SplitBrainWithAccrualFailureDetectorMultiJvmNode2 extends SplitBrainSpec with AccrualFailureDetectorStrategy
class SplitBrainWithAccrualFailureDetectorMultiJvmNode3 extends SplitBrainSpec with AccrualFailureDetectorStrategy
class SplitBrainWithAccrualFailureDetectorMultiJvmNode4 extends SplitBrainSpec with AccrualFailureDetectorStrategy
class SplitBrainWithAccrualFailureDetectorMultiJvmNode5 extends SplitBrainSpec with AccrualFailureDetectorStrategy

abstract class SplitBrainSpec
  extends MultiNodeSpec(SplitBrainMultiJvmSpec)
  with MultiNodeClusterSpec {

  import SplitBrainMultiJvmSpec._

  val side1 = IndexedSeq(first, second)
  val side2 = IndexedSeq(third, fourth, fifth)

  "A cluster of 5 members" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth, fifth)

      enterBarrier("after-1")
    }

    "detect network partition and mark nodes on other side as unreachable" taggedAs LongRunningTest in {
      val thirdAddress = address(third)
      enterBarrier("before-split")

      runOn(first) {
        // split the cluster in two parts (first, second) / (third, fourth, fifth)
        for (role1 ← side1; role2 ← side2) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("after-split")

      runOn(side1.last) {
        for (role ← side2) markNodeAsUnavailable(role)
      }
      runOn(side2.last) {
        for (role ← side1) markNodeAsUnavailable(role)
      }

      runOn(side1: _*) {
        awaitCond(cluster.latestGossip.overview.unreachable.map(_.address) == (side2.toSet map address), 20 seconds)
      }
      runOn(side2: _*) {
        awaitCond(cluster.latestGossip.overview.unreachable.map(_.address) == (side1.toSet map address), 20 seconds)
      }

      enterBarrier("after-2")
    }

    "auto-down the other nodes and form new cluster with potentially new leader" taggedAs LongRunningTest in {

      runOn(side1: _*) {
        // auto-down = on
        awaitCond(cluster.latestGossip.overview.unreachable.forall(m ⇒ m.status == MemberStatus.Down), 15 seconds)
        cluster.latestGossip.overview.unreachable.map(_.address) must be(side2.toSet map address)
        awaitUpConvergence(side1.size, side2 map address)
        assertLeader(side1: _*)
      }

      runOn(side2: _*) {
        // auto-down = on
        awaitCond(cluster.latestGossip.overview.unreachable.forall(m ⇒ m.status == MemberStatus.Down), 15 seconds)
        cluster.latestGossip.overview.unreachable.map(_.address) must be(side1.toSet map address)
        awaitUpConvergence(side2.size, side1 map address)
        assertLeader(side2: _*)
      }

      enterBarrier("after-3")
    }

  }

}
