/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._

object LeaderLeavingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
        akka.cluster {
          leader-actions-interval           = 5 s  # increase the leader action task frequency to make sure we get a chance to test the LEAVING state
          unreachable-nodes-reaper-interval = 30 s
        }
      """)
        .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class LeaderLeavingMultiJvmNode1 extends LeaderLeavingSpec with FailureDetectorPuppetStrategy
class LeaderLeavingMultiJvmNode2 extends LeaderLeavingSpec with FailureDetectorPuppetStrategy
class LeaderLeavingMultiJvmNode3 extends LeaderLeavingSpec with FailureDetectorPuppetStrategy

abstract class LeaderLeavingSpec
  extends MultiNodeSpec(LeaderLeavingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import LeaderLeavingMultiJvmSpec._

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address
  lazy val thirdAddress = node(third).address

  val reaperWaitingTime = 30.seconds.dilated

  def leaderRole = cluster.leader match {
    case `firstAddress`  => first
    case `secondAddress` => second
    case `thirdAddress`  => third
  }

  "A LEADER that is LEAVING" must {

    "be moved to LEAVING, then to EXITING, then to REMOVED, then be shut down and then a new LEADER should be elected" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      val oldLeaderAddress = cluster.leader

      if (cluster.isLeader) {

        cluster.leave(oldLeaderAddress)
        testConductor.enter("leader-left")

        // verify that the LEADER is shut down
        awaitCond(!cluster.isRunning, reaperWaitingTime)

        // verify that the LEADER is REMOVED
        awaitCond(cluster.status == MemberStatus.Removed, reaperWaitingTime)

      } else {

        testConductor.enter("leader-left")

        // verify that the LEADER is LEAVING
        awaitCond(cluster.latestGossip.members.exists(m => m.status == MemberStatus.Leaving && m.address == oldLeaderAddress)) // wait on LEAVING

        // verify that the LEADER is EXITING
        awaitCond(cluster.latestGossip.members.exists(m => m.status == MemberStatus.Exiting && m.address == oldLeaderAddress)) // wait on EXITING

        // verify that the LEADER is no longer part of the 'members' set
        awaitCond(cluster.latestGossip.members.forall(_.address != oldLeaderAddress), reaperWaitingTime)

        // verify that the LEADER is not part of the 'unreachable' set
        awaitCond(cluster.latestGossip.overview.unreachable.forall(_.address != oldLeaderAddress), reaperWaitingTime)

        // verify that we have a new LEADER
        awaitCond(cluster.leader != oldLeaderAddress, reaperWaitingTime)
      }

      testConductor.enter("finished")
    }
  }
}
