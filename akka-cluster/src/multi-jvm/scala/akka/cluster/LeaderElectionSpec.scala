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

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
        akka.cluster.auto-down = off
        """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

}

class LeaderElectionMultiJvmNode1 extends LeaderElectionSpec
class LeaderElectionMultiJvmNode2 extends LeaderElectionSpec
class LeaderElectionMultiJvmNode3 extends LeaderElectionSpec
class LeaderElectionMultiJvmNode4 extends LeaderElectionSpec
class LeaderElectionMultiJvmNode5 extends LeaderElectionSpec

abstract class LeaderElectionSpec extends MultiNodeSpec(LeaderElectionMultiJvmSpec) with MultiNodeClusterSpec {
  import LeaderElectionMultiJvmSpec._

  override def initialParticipants = 5

  lazy val firstAddress = node(first).address

  // sorted in the order used by the cluster
  lazy val roles = Seq(first, second, third, fourth).sorted

  "A cluster of four nodes" must {

    "be able to 'elect' a single leader" taggedAs LongRunningTest in {
      // make sure that the node-to-join is started before other join
      runOn(first) {
        cluster
      }
      testConductor.enter("first-started")

      if (mySelf != controller) {
        cluster.join(firstAddress)
        awaitUpConvergence(numberOfMembers = roles.size)
        cluster.isLeader must be(mySelf == roles.head)
      }
      testConductor.enter("after")
    }

    def shutdownLeaderAndVerifyNewLeader(alreadyShutdown: Int): Unit = {
      val currentRoles = roles.drop(alreadyShutdown)
      currentRoles.size must be >= (2)
      val leader = currentRoles.head
      val aUser = currentRoles.last

      mySelf match {

        case `controller` ⇒
          testConductor.enter("before-shutdown")
          testConductor.shutdown(leader, 0)
          testConductor.removeNode(leader)
          testConductor.enter("after-shutdown", "after-down", "completed")

        case `leader` ⇒
          testConductor.enter("before-shutdown")
        // this node will be shutdown by the controller and doesn't participate in more barriers

        case `aUser` ⇒
          val leaderAddress = node(leader).address
          testConductor.enter("before-shutdown", "after-shutdown")
          // user marks the shutdown leader as DOWN
          cluster.down(leaderAddress)
          testConductor.enter("after-down", "completed")

        case _ if currentRoles.tail.contains(mySelf) ⇒
          // remaining cluster nodes, not shutdown
          testConductor.enter("before-shutdown", "after-shutdown", "after-down")

          awaitUpConvergence(currentRoles.size - 1)
          val nextExpectedLeader = currentRoles.tail.head
          cluster.isLeader must be(mySelf == nextExpectedLeader)

          testConductor.enter("completed")

      }

    }

    "be able to 're-elect' a single leader after leader has left" taggedAs LongRunningTest in {
      shutdownLeaderAndVerifyNewLeader(alreadyShutdown = 0)
    }

    "be able to 're-elect' a single leader after leader has left (again)" taggedAs LongRunningTest in {
      shutdownLeaderAndVerifyNewLeader(alreadyShutdown = 1)
    }
  }

}
