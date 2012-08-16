/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object LeaderElectionMultiJvmSpec extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class LeaderElectionWithFailureDetectorPuppetMultiJvmNode1 extends LeaderElectionSpec with FailureDetectorPuppetStrategy
class LeaderElectionWithFailureDetectorPuppetMultiJvmNode2 extends LeaderElectionSpec with FailureDetectorPuppetStrategy
class LeaderElectionWithFailureDetectorPuppetMultiJvmNode3 extends LeaderElectionSpec with FailureDetectorPuppetStrategy
class LeaderElectionWithFailureDetectorPuppetMultiJvmNode4 extends LeaderElectionSpec with FailureDetectorPuppetStrategy
class LeaderElectionWithFailureDetectorPuppetMultiJvmNode5 extends LeaderElectionSpec with FailureDetectorPuppetStrategy

class LeaderElectionWithAccrualFailureDetectorMultiJvmNode1 extends LeaderElectionSpec with AccrualFailureDetectorStrategy
class LeaderElectionWithAccrualFailureDetectorMultiJvmNode2 extends LeaderElectionSpec with AccrualFailureDetectorStrategy
class LeaderElectionWithAccrualFailureDetectorMultiJvmNode3 extends LeaderElectionSpec with AccrualFailureDetectorStrategy
class LeaderElectionWithAccrualFailureDetectorMultiJvmNode4 extends LeaderElectionSpec with AccrualFailureDetectorStrategy
class LeaderElectionWithAccrualFailureDetectorMultiJvmNode5 extends LeaderElectionSpec with AccrualFailureDetectorStrategy

abstract class LeaderElectionSpec
  extends MultiNodeSpec(LeaderElectionMultiJvmSpec)
  with MultiNodeClusterSpec {

  import LeaderElectionMultiJvmSpec._

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

      myself match {

        case `controller` ⇒
          val leaderAddress = address(leader)
          enterBarrier("before-shutdown")
          testConductor.shutdown(leader, 0)
          enterBarrier("after-shutdown", "after-down", "completed")
          markNodeAsUnavailable(leaderAddress)

        case `leader` ⇒
          enterBarrier("before-shutdown", "after-shutdown")
        // this node will be shutdown by the controller and doesn't participate in more barriers

        case `aUser` ⇒
          val leaderAddress = address(leader)
          enterBarrier("before-shutdown", "after-shutdown")
          // user marks the shutdown leader as DOWN
          cluster.down(leaderAddress)
          enterBarrier("after-down", "completed")
          markNodeAsUnavailable(leaderAddress)

        case _ if remainingRoles.contains(myself) ⇒
          // remaining cluster nodes, not shutdown
          enterBarrier("before-shutdown", "after-shutdown", "after-down")

          awaitUpConvergence(currentRoles.size - 1)
          val nextExpectedLeader = remainingRoles.head
          clusterView.isLeader must be(myself == nextExpectedLeader)
          assertLeaderIn(remainingRoles)

          enterBarrier("completed")

      }
    }

    "be able to 're-elect' a single leader after leader has left" taggedAs LongRunningTest in {
      shutdownLeaderAndVerifyNewLeader(alreadyShutdown = 0)
      enterBarrier("after-2")
    }

    "be able to 're-elect' a single leader after leader has left (again)" taggedAs LongRunningTest in {
      shutdownLeaderAndVerifyNewLeader(alreadyShutdown = 1)
      enterBarrier("after-3")
    }
  }
}
