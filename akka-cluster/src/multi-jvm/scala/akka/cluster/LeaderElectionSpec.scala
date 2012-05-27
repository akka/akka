/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object LeaderElectionMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val forth = role("forth")

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

abstract class LeaderElectionSpec extends MultiNodeSpec(LeaderElectionMultiJvmSpec) with MultiNodeClusterSpec {
  import LeaderElectionMultiJvmSpec._

  override def initialParticipants = 4

  lazy val firstAddress = node(first).address

  // sorted in the order used by the cluster
  lazy val roles = Seq(first, second, third, forth).sorted

  "A cluster of four nodes" must {

    "be able to 'elect' a single leader" taggedAs LongRunningTest in {
      // make sure that the first cluster is started before other join
      runOn(first) {
        cluster
      }
      testConductor.enter("first-started")

      cluster.join(firstAddress)
      awaitUpConvergence(numberOfMembers = 4)
      cluster.isLeader must be(mySelf == roles.head)
      testConductor.enter("after")
    }

    def shutdownLeaderAndVerifyNewLeader(alreadyShutdown: Int): Unit = {
      val currentRoles = roles.drop(alreadyShutdown)
      currentRoles.size must be >= (2)

      runOn(currentRoles.head) {
        cluster.shutdown()
        testConductor.enter("after-shutdown")
        testConductor.enter("after-down")
      }

      // runOn previously shutdown cluster nodes
      if ((roles diff currentRoles).contains(mySelf)) {
        testConductor.enter("after-shutdown")
        testConductor.enter("after-down")
      }

      // runOn remaining cluster nodes
      if (currentRoles.tail.contains(mySelf)) {

        testConductor.enter("after-shutdown")

        runOn(currentRoles.last) {
          // user marks the shutdown leader as DOWN
          val leaderAddress = node(currentRoles.head).address
          cluster.down(leaderAddress)
        }

        testConductor.enter("after-down")

        awaitUpConvergence(currentRoles.size - 1)
        val nextExpectedLeader = currentRoles.tail.head
        cluster.isLeader must be(mySelf == nextExpectedLeader)
      }

      testConductor.enter("after")
    }

    "be able to 're-elect' a single leader after leader has left" taggedAs LongRunningTest in {
      shutdownLeaderAndVerifyNewLeader(alreadyShutdown = 0)
    }

    "be able to 're-elect' a single leader after leader has left (again)" taggedAs LongRunningTest in {
      shutdownLeaderAndVerifyNewLeader(alreadyShutdown = 1)
    }
  }

}
