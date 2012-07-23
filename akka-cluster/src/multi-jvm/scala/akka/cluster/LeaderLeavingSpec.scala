/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.util.duration._

object LeaderLeavingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
          # turn off unreachable reaper
          akka.cluster.unreachable-nodes-reaper-interval = 300 s""")
        .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class LeaderLeavingMultiJvmNode1 extends LeaderLeavingSpec with FailureDetectorPuppetStrategy
class LeaderLeavingMultiJvmNode2 extends LeaderLeavingSpec with FailureDetectorPuppetStrategy
class LeaderLeavingMultiJvmNode3 extends LeaderLeavingSpec with FailureDetectorPuppetStrategy

abstract class LeaderLeavingSpec
  extends MultiNodeSpec(LeaderLeavingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import LeaderLeavingMultiJvmSpec._

  val leaderHandoffWaitingTime = 30.seconds

  "A LEADER that is LEAVING" must {

    "be moved to LEAVING, then to EXITING, then to REMOVED, then be shut down and then a new LEADER should be elected" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      val oldLeaderAddress = cluster.leader

      within(leaderHandoffWaitingTime) {

        if (cluster.isLeader) {

          enterBarrier("registered-listener")

          cluster.leave(oldLeaderAddress)
          enterBarrier("leader-left")

          // verify that a NEW LEADER have taken over
          awaitCond(!cluster.isLeader)

          // verify that the LEADER is shut down
          awaitCond(!cluster.isRunning)

          // verify that the LEADER is REMOVED
          awaitCond(cluster.status == MemberStatus.Removed)

        } else {

          val leavingLatch = TestLatch()
          val exitingLatch = TestLatch()
          val expectedAddresses = roles.toSet map address
          cluster.registerListener(new MembershipChangeListener {
            def notify(members: SortedSet[Member]) {
              def check(status: MemberStatus): Boolean =
                (members.map(_.address) == expectedAddresses &&
                  members.exists(m ⇒ m.address == oldLeaderAddress && m.status == status))
              if (check(MemberStatus.Leaving)) leavingLatch.countDown()
              if (check(MemberStatus.Exiting)) exitingLatch.countDown()
            }
          })
          enterBarrier("registered-listener")

          enterBarrier("leader-left")

          // verify that the LEADER is LEAVING
          leavingLatch.await

          // verify that the LEADER is EXITING
          exitingLatch.await

          // verify that the LEADER is no longer part of the 'members' set
          awaitCond(cluster.latestGossip.members.forall(_.address != oldLeaderAddress))

          // verify that the LEADER is not part of the 'unreachable' set
          awaitCond(cluster.latestGossip.overview.unreachable.forall(_.address != oldLeaderAddress))

          // verify that we have a new LEADER
          awaitCond(cluster.leader != oldLeaderAddress)
        }

        enterBarrier("finished")
      }
    }
  }
}
