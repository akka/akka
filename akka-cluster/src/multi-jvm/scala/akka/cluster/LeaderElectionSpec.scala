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

case class LeaderElectionMultiNodeConfig(failureDetectorPuppet: Boolean) extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = true).withFallback(MultiNodeClusterSpec.clusterConfig(failureDetectorPuppet)))
}

class LeaderElectionWithFailureDetectorPuppetMultiJvmNode1 extends LeaderElectionSpec(failureDetectorPuppet = true)
class LeaderElectionWithFailureDetectorPuppetMultiJvmNode2 extends LeaderElectionSpec(failureDetectorPuppet = true)
class LeaderElectionWithFailureDetectorPuppetMultiJvmNode3 extends LeaderElectionSpec(failureDetectorPuppet = true)
class LeaderElectionWithFailureDetectorPuppetMultiJvmNode4 extends LeaderElectionSpec(failureDetectorPuppet = true)
class LeaderElectionWithFailureDetectorPuppetMultiJvmNode5 extends LeaderElectionSpec(failureDetectorPuppet = true)

class LeaderElectionWithAccrualFailureDetectorMultiJvmNode1 extends LeaderElectionSpec(failureDetectorPuppet = false)
class LeaderElectionWithAccrualFailureDetectorMultiJvmNode2 extends LeaderElectionSpec(failureDetectorPuppet = false)
class LeaderElectionWithAccrualFailureDetectorMultiJvmNode3 extends LeaderElectionSpec(failureDetectorPuppet = false)
class LeaderElectionWithAccrualFailureDetectorMultiJvmNode4 extends LeaderElectionSpec(failureDetectorPuppet = false)
class LeaderElectionWithAccrualFailureDetectorMultiJvmNode5 extends LeaderElectionSpec(failureDetectorPuppet = false)

abstract class LeaderElectionSpec(multiNodeConfig: LeaderElectionMultiNodeConfig)
  extends MultiNodeSpec(multiNodeConfig)
  with MultiNodeClusterSpec {

  def this(failureDetectorPuppet: Boolean) = this(LeaderElectionMultiNodeConfig(failureDetectorPuppet))

  import multiNodeConfig._

  // sorted in the order used by the cluster
  lazy val sortedRoles = Seq(first, second, third, fourth).sorted

  "A cluster of four nodes" must {

    "be able to 'elect' a single leader" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth)

      if (myself != controller) {
        clusterView.isLeader must be(myself == sortedRoles.head)
        assertLeaderIn(sortedRoles)
      }

      enterBarrier("after-1")
    }

    def shutdownLeaderAndVerifyNewLeader(alreadyShutdown: Int): Unit = {
      val currentRoles = sortedRoles.drop(alreadyShutdown)
      currentRoles.size must be >= (2)
      val leader = currentRoles.head
      val aUser = currentRoles.last
      val remainingRoles = currentRoles.tail
      val n = "-" + (alreadyShutdown + 1)

      myself match {

        case `controller` ⇒
          val leaderAddress = address(leader)
          enterBarrier("before-shutdown" + n)
          testConductor.shutdown(leader, 0)
          enterBarrier("after-shutdown" + n, "after-unavailable" + n, "after-down" + n, "completed" + n)

        case `leader` ⇒
          enterBarrier("before-shutdown" + n, "after-shutdown" + n)
        // this node will be shutdown by the controller and doesn't participate in more barriers

        case `aUser` ⇒
          val leaderAddress = address(leader)
          enterBarrier("before-shutdown" + n, "after-shutdown" + n)

          // detect failure
          markNodeAsUnavailable(leaderAddress)
          awaitCond(clusterView.unreachableMembers.exists(m ⇒ m.address == leaderAddress))
          enterBarrier("after-unavailable" + n)

          // user marks the shutdown leader as DOWN
          cluster.down(leaderAddress)
          enterBarrier("after-down" + n, "completed" + n)

        case _ if remainingRoles.contains(myself) ⇒
          // remaining cluster nodes, not shutdown
          val leaderAddress = address(leader)
          enterBarrier("before-shutdown" + n, "after-shutdown" + n)

          awaitCond(clusterView.unreachableMembers.exists(m ⇒ m.address == leaderAddress))
          enterBarrier("after-unavailable" + n)

          enterBarrier("after-down" + n)
          awaitUpConvergence(currentRoles.size - 1)
          val nextExpectedLeader = remainingRoles.head
          clusterView.isLeader must be(myself == nextExpectedLeader)
          assertLeaderIn(remainingRoles)

          enterBarrier("completed" + n)

      }
    }

    "be able to 're-elect' a single leader after leader has left" taggedAs LongRunningTest in within(20 seconds) {
      shutdownLeaderAndVerifyNewLeader(alreadyShutdown = 0)
      enterBarrier("after-2")
    }

    "be able to 're-elect' a single leader after leader has left (again)" taggedAs LongRunningTest in within(20 seconds) {
      shutdownLeaderAndVerifyNewLeader(alreadyShutdown = 1)
      enterBarrier("after-3")
    }
  }
}
