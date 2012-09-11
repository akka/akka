/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.util.duration._
import akka.actor.Address

case class ConvergenceMultiNodeConfig(failureDetectorPuppet: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.failure-detector.threshold = 4")).
    withFallback(MultiNodeClusterSpec.clusterConfig(failureDetectorPuppet)))
}

class ConvergenceWithFailureDetectorPuppetMultiJvmNode1 extends ConvergenceSpec(failureDetectorPuppet = true)
class ConvergenceWithFailureDetectorPuppetMultiJvmNode2 extends ConvergenceSpec(failureDetectorPuppet = true)
class ConvergenceWithFailureDetectorPuppetMultiJvmNode3 extends ConvergenceSpec(failureDetectorPuppet = true)
class ConvergenceWithFailureDetectorPuppetMultiJvmNode4 extends ConvergenceSpec(failureDetectorPuppet = true)

class ConvergenceWithAccrualFailureDetectorMultiJvmNode1 extends ConvergenceSpec(failureDetectorPuppet = false)
class ConvergenceWithAccrualFailureDetectorMultiJvmNode2 extends ConvergenceSpec(failureDetectorPuppet = false)
class ConvergenceWithAccrualFailureDetectorMultiJvmNode3 extends ConvergenceSpec(failureDetectorPuppet = false)
class ConvergenceWithAccrualFailureDetectorMultiJvmNode4 extends ConvergenceSpec(failureDetectorPuppet = false)

abstract class ConvergenceSpec(multiNodeConfig: ConvergenceMultiNodeConfig)
  extends MultiNodeSpec(multiNodeConfig)
  with MultiNodeClusterSpec {

  def this(failureDetectorPuppet: Boolean) = this(ConvergenceMultiNodeConfig(failureDetectorPuppet))

  import multiNodeConfig._

  "A cluster of 3 members" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)

      runOn(fourth) {
        // doesn't join immediately
      }

      enterBarrier("after-1")
    }

    "not reach convergence while any nodes are unreachable" taggedAs LongRunningTest in {
      val thirdAddress = address(third)
      enterBarrier("before-shutdown")

      runOn(first) {
        // kill 'third' node
        testConductor.shutdown(third, 0)
        markNodeAsUnavailable(thirdAddress)
      }

      runOn(first, second) {

        within(28 seconds) {
          // third becomes unreachable
          awaitCond(clusterView.unreachableMembers.size == 1)
          awaitCond(clusterView.members.size == 2)
          awaitCond(clusterView.members.forall(_.status == MemberStatus.Up))
          awaitSeenSameState(first, second)
          // still one unreachable
          clusterView.unreachableMembers.size must be(1)
          clusterView.unreachableMembers.head.address must be(thirdAddress)
          // and therefore no convergence
          clusterView.convergence must be(false)

        }
      }

      enterBarrier("after-2")
    }

    "not move a new joining node to Up while there is no convergence" taggedAs LongRunningTest in {
      runOn(fourth) {
        // try to join
        cluster.join(first)
      }

      def memberStatus(address: Address): Option[MemberStatus] =
        clusterView.members.collectFirst { case m if m.address == address ⇒ m.status }

      def assertNotMovedUp: Unit = {
        within(20 seconds) {
          awaitCond(clusterView.members.size == 3)
          awaitSeenSameState(first, second, fourth)
          memberStatus(first) must be(Some(MemberStatus.Up))
          memberStatus(second) must be(Some(MemberStatus.Up))
          // leader is not allowed to move the new node to Up
          memberStatus(fourth) must be(Some(MemberStatus.Joining))
          // still no convergence
          clusterView.convergence must be(false)
        }
      }

      runOn(first, second, fourth) {
        for (n ← 1 to 5) {
          log.debug("assertNotMovedUp#" + n)
          assertNotMovedUp
          // wait and then check again
          Thread.sleep(1.second.dilated.toMillis)
        }
      }

      enterBarrier("after-3")
    }
  }
}
