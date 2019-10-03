/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import language.postfixOps

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent.duration._
import akka.remote.transport.ThrottlerTransportAdapter.Direction

final case class SplitBrainMultiNodeConfig(failureDetectorPuppet: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
        akka.remote.retry-gate-closed-for = 3 s
        akka.cluster {
          downing-provider-class = akka.cluster.testkit.AutoDowning
          testkit.auto-down-unreachable-after = 1s
          failure-detector.threshold = 4
        }"""))
      .withFallback(MultiNodeClusterSpec.clusterConfig(failureDetectorPuppet)))

  testTransport(on = true)
}

class SplitBrainWithFailureDetectorPuppetMultiJvmNode1 extends SplitBrainSpec(failureDetectorPuppet = true)
class SplitBrainWithFailureDetectorPuppetMultiJvmNode2 extends SplitBrainSpec(failureDetectorPuppet = true)
class SplitBrainWithFailureDetectorPuppetMultiJvmNode3 extends SplitBrainSpec(failureDetectorPuppet = true)
class SplitBrainWithFailureDetectorPuppetMultiJvmNode4 extends SplitBrainSpec(failureDetectorPuppet = true)
class SplitBrainWithFailureDetectorPuppetMultiJvmNode5 extends SplitBrainSpec(failureDetectorPuppet = true)

class SplitBrainWithAccrualFailureDetectorMultiJvmNode1 extends SplitBrainSpec(failureDetectorPuppet = false)
class SplitBrainWithAccrualFailureDetectorMultiJvmNode2 extends SplitBrainSpec(failureDetectorPuppet = false)
class SplitBrainWithAccrualFailureDetectorMultiJvmNode3 extends SplitBrainSpec(failureDetectorPuppet = false)
class SplitBrainWithAccrualFailureDetectorMultiJvmNode4 extends SplitBrainSpec(failureDetectorPuppet = false)
class SplitBrainWithAccrualFailureDetectorMultiJvmNode5 extends SplitBrainSpec(failureDetectorPuppet = false)

abstract class SplitBrainSpec(multiNodeConfig: SplitBrainMultiNodeConfig)
    extends MultiNodeSpec(multiNodeConfig)
    with MultiNodeClusterSpec {

  def this(failureDetectorPuppet: Boolean) = this(SplitBrainMultiNodeConfig(failureDetectorPuppet))

  import multiNodeConfig._

  muteMarkingAsUnreachable()

  val side1 = Vector(first, second)
  val side2 = Vector(third, fourth, fifth)

  "A cluster of 5 members" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth, fifth)

      enterBarrier("after-1")
    }

    "detect network partition and mark nodes on other side as unreachable and form new cluster" taggedAs LongRunningTest in within(
      30 seconds) {
      enterBarrier("before-split")

      runOn(first) {
        // split the cluster in two parts (first, second) / (third, fourth, fifth)
        for (role1 <- side1; role2 <- side2) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("after-split")

      runOn(side1: _*) {
        for (role <- side2) markNodeAsUnavailable(role)
        // auto-down
        awaitMembersUp(side1.size, side2.toSet.map(address))
        assertLeader(side1: _*)
      }

      runOn(side2: _*) {
        for (role <- side1) markNodeAsUnavailable(role)
        // auto-down
        awaitMembersUp(side2.size, side1.toSet.map(address))
        assertLeader(side2: _*)
      }

      enterBarrier("after-2")
    }

  }

}
