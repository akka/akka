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
import akka.actor.Props
import akka.actor.Actor
import akka.cluster.MemberStatus._

object LeaderLeavingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
          # turn off unreachable reaper
          akka.cluster.unreachable-nodes-reaper-interval = 300 s""")
        .withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet)))
}

class LeaderLeavingMultiJvmNode1 extends LeaderLeavingSpec
class LeaderLeavingMultiJvmNode2 extends LeaderLeavingSpec
class LeaderLeavingMultiJvmNode3 extends LeaderLeavingSpec

abstract class LeaderLeavingSpec
  extends MultiNodeSpec(LeaderLeavingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import LeaderLeavingMultiJvmSpec._
  import ClusterEvent._

  val leaderHandoffWaitingTime = 30.seconds

  "A LEADER that is LEAVING" must {

    "be moved to LEAVING, then to EXITING, then to REMOVED, then be shut down and then a new LEADER should be elected" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      val oldLeaderAddress = clusterView.leader.get

      within(leaderHandoffWaitingTime) {

        if (clusterView.isLeader) {

          enterBarrier("registered-listener")

          cluster.leave(oldLeaderAddress)
          enterBarrier("leader-left")

          // verify that the LEADER is shut down
          awaitCond(!cluster.isRunning)

          // verify that the LEADER is REMOVED
          awaitCond(clusterView.status == Removed)

        } else {

          val leavingLatch = TestLatch()
          val exitingLatch = TestLatch()

          cluster.subscribe(system.actorOf(Props(new Actor {
            def receive = {
              case state: CurrentClusterState ⇒
                if (state.members.exists(m ⇒ m.address == oldLeaderAddress && m.status == Leaving))
                  leavingLatch.countDown()
                if (state.members.exists(m ⇒ m.address == oldLeaderAddress && m.status == Exiting))
                  exitingLatch.countDown()
              case MemberLeft(m) if m.address == oldLeaderAddress ⇒ leavingLatch.countDown()
              case MemberExited(m) if m.address == oldLeaderAddress ⇒ exitingLatch.countDown()
              case _ ⇒ // ignore
            }
          })), classOf[MemberEvent])
          enterBarrier("registered-listener")

          enterBarrier("leader-left")

          val expectedAddresses = roles.toSet map address
          awaitCond(clusterView.members.map(_.address) == expectedAddresses)

          // verify that the LEADER is LEAVING
          leavingLatch.await

          // verify that the LEADER is EXITING
          exitingLatch.await

          // verify that the LEADER is no longer part of the 'members' set
          awaitCond(clusterView.members.forall(_.address != oldLeaderAddress))

          // verify that the LEADER is not part of the 'unreachable' set
          awaitCond(clusterView.unreachableMembers.forall(_.address != oldLeaderAddress))

          // verify that we have a new LEADER
          awaitCond(clusterView.leader != oldLeaderAddress)
        }

        enterBarrier("finished")
      }
    }
  }
}
