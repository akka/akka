/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor._
import scala.concurrent.duration._
import scala.collection.immutable

case class LeaderDowningNodeThatIsUnreachableMultiNodeConfig(failureDetectorPuppet: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.auto-down = on")).
    withFallback(MultiNodeClusterSpec.clusterConfig(failureDetectorPuppet)))
}

class LeaderDowningNodeThatIsUnreachableWithFailureDetectorPuppetMultiJvmNode1 extends LeaderDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = true)
class LeaderDowningNodeThatIsUnreachableWithFailureDetectorPuppetMultiJvmNode2 extends LeaderDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = true)
class LeaderDowningNodeThatIsUnreachableWithFailureDetectorPuppetMultiJvmNode3 extends LeaderDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = true)
class LeaderDowningNodeThatIsUnreachableWithFailureDetectorPuppetMultiJvmNode4 extends LeaderDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = true)

class LeaderDowningNodeThatIsUnreachableWithAccrualFailureDetectorMultiJvmNode1 extends LeaderDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = false)
class LeaderDowningNodeThatIsUnreachableWithAccrualFailureDetectorMultiJvmNode2 extends LeaderDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = false)
class LeaderDowningNodeThatIsUnreachableWithAccrualFailureDetectorMultiJvmNode3 extends LeaderDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = false)
class LeaderDowningNodeThatIsUnreachableWithAccrualFailureDetectorMultiJvmNode4 extends LeaderDowningNodeThatIsUnreachableSpec(failureDetectorPuppet = false)

abstract class LeaderDowningNodeThatIsUnreachableSpec(multiNodeConfig: LeaderDowningNodeThatIsUnreachableMultiNodeConfig)
  extends MultiNodeSpec(multiNodeConfig)
  with MultiNodeClusterSpec {

  def this(failureDetectorPuppet: Boolean) = this(LeaderDowningNodeThatIsUnreachableMultiNodeConfig(failureDetectorPuppet))

  import multiNodeConfig._

  muteMarkingAsUnreachable()

  "The Leader in a 4 node cluster" must {

    "be able to DOWN a 'last' node that is UNREACHABLE" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth)

      val fourthAddress = address(fourth)
      runOn(first) {
        // kill 'fourth' node
        testConductor.shutdown(fourth, 0).await
        enterBarrier("down-fourth-node")

        // mark the node as unreachable in the failure detector
        markNodeAsUnavailable(fourthAddress)

        // --- HERE THE LEADER SHOULD DETECT FAILURE AND AUTO-DOWN THE UNREACHABLE NODE ---

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = List(fourthAddress), 30.seconds)
      }

      runOn(fourth) {
        enterBarrier("down-fourth-node")
      }

      runOn(second, third) {
        enterBarrier("down-fourth-node")

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = List(fourthAddress), 30.seconds)
      }

      enterBarrier("await-completion-1")
    }

    "be able to DOWN a 'middle' node that is UNREACHABLE" taggedAs LongRunningTest in {
      val secondAddress = address(second)

      enterBarrier("before-down-second-node")
      runOn(first) {
        // kill 'second' node
        testConductor.shutdown(second, 0).await
        enterBarrier("down-second-node")

        // mark the node as unreachable in the failure detector
        markNodeAsUnavailable(secondAddress)

        // --- HERE THE LEADER SHOULD DETECT FAILURE AND AUTO-DOWN THE UNREACHABLE NODE ---

        awaitUpConvergence(numberOfMembers = 2, canNotBePartOfMemberRing = List(secondAddress), 30.seconds)
      }

      runOn(second) {
        enterBarrier("down-second-node")
      }

      runOn(third) {
        enterBarrier("down-second-node")

        awaitUpConvergence(numberOfMembers = 2, canNotBePartOfMemberRing = List(secondAddress), 30 seconds)
      }

      enterBarrier("await-completion-2")
    }
  }
}
