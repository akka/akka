/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.implicitConversions

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.Address
import akka.pattern.ask
import akka.remote.testconductor.RoleName
import MemberStatus._
import InternalClusterAction._

object TransitionMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.periodic-tasks-initial-delay = 300 s # turn off all periodic tasks")).
    withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class TransitionMultiJvmNode1 extends TransitionSpec
class TransitionMultiJvmNode2 extends TransitionSpec
class TransitionMultiJvmNode3 extends TransitionSpec

abstract class TransitionSpec
  extends MultiNodeSpec(TransitionMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender {

  import TransitionMultiJvmSpec._

  muteMarkingAsUnreachable()

  // sorted in the order used by the cluster
  def leader(roles: RoleName*) = roles.sorted.head
  def nonLeader(roles: RoleName*) = roles.toSeq.sorted.tail

  def memberStatus(address: Address): MemberStatus = {
    val statusOption = (clusterView.members ++ clusterView.unreachableMembers).collectFirst {
      case m if m.address == address ⇒ m.status
    }
    statusOption.getOrElse(Removed)
  }

  def memberAddresses: Set[Address] = clusterView.members.map(_.address)

  def members: Set[RoleName] = memberAddresses.flatMap(roleName(_))

  def seenLatestGossip: Set[RoleName] = clusterView.seenBy flatMap roleName

  def awaitSeen(addresses: Address*): Unit = awaitCond {
    (seenLatestGossip map address) == addresses.toSet
  }

  def awaitMembers(addresses: Address*): Unit = awaitCond {
    val result = memberAddresses == addresses.toSet
    clusterView.refreshCurrentState()
    result
  }

  def awaitMemberStatus(address: Address, status: MemberStatus): Unit = awaitCond {
    val result = memberStatus(address) == status
    clusterView.refreshCurrentState()
    result
  }

  def leaderActions(): Unit =
    cluster.clusterCore ! LeaderActionsTick

  def reapUnreachable(): Unit =
    cluster.clusterCore ! ReapUnreachableTick

  // DSL sugar for `role1 gossipTo role2`
  implicit def roleExtras(role: RoleName): RoleWrapper = new RoleWrapper(role)
  var gossipBarrierCounter = 0
  class RoleWrapper(fromRole: RoleName) {
    def gossipTo(toRole: RoleName): Unit = {
      gossipBarrierCounter += 1
      runOn(toRole) {
        val oldCount = clusterView.latestStats.receivedGossipCount
        enterBarrier("before-gossip-" + gossipBarrierCounter)
        awaitCond {
          clusterView.latestStats.receivedGossipCount != oldCount // received gossip
        }
        // gossip chat will synchronize the views
        awaitCond((Set(fromRole, toRole) -- seenLatestGossip).isEmpty)
        enterBarrier("after-gossip-" + gossipBarrierCounter)
      }
      runOn(fromRole) {
        enterBarrier("before-gossip-" + gossipBarrierCounter)
        // send gossip
        cluster.clusterCore ! InternalClusterAction.SendGossipTo(toRole)
        // gossip chat will synchronize the views
        awaitCond((Set(fromRole, toRole) -- seenLatestGossip).isEmpty)
        enterBarrier("after-gossip-" + gossipBarrierCounter)
      }
      runOn(roles.filterNot(r ⇒ r == fromRole || r == toRole): _*) {
        enterBarrier("before-gossip-" + gossipBarrierCounter)
        enterBarrier("after-gossip-" + gossipBarrierCounter)
      }
    }
  }

  "A Cluster" must {

    "start nodes as singleton clusters" taggedAs LongRunningTest in {

      runOn(first) {
        cluster join myself
        awaitMemberStatus(myself, Joining)
        leaderActions()
        awaitMemberStatus(myself, Up)
        awaitCond(clusterView.isSingletonCluster)
      }

      enterBarrier("after-1")
    }

    "perform correct transitions when second joining first" taggedAs LongRunningTest in {

      runOn(second) {
        cluster.join(first)
      }
      runOn(first, second) {
        // gossip chat from the join will synchronize the views
        awaitMembers(first, second)
        awaitMemberStatus(first, Up)
        awaitMemberStatus(second, Joining)
        awaitCond(seenLatestGossip == Set(first, second))
      }
      enterBarrier("convergence-joining-2")

      runOn(leader(first, second)) {
        leaderActions()
        awaitMemberStatus(first, Up)
        awaitMemberStatus(second, Joining)
      }
      enterBarrier("leader-actions-2")

      leader(first, second) gossipTo nonLeader(first, second).head
      runOn(first, second) {
        // gossip chat will synchronize the views
        awaitMemberStatus(second, Up)
        awaitCond(seenLatestGossip == Set(first, second))
        awaitMemberStatus(first, Up)
      }

      enterBarrier("after-2")
    }

    "perform correct transitions when third joins second" taggedAs LongRunningTest in {

      runOn(third) {
        cluster.join(second)
      }
      runOn(second, third) {
        // gossip chat from the join will synchronize the views
        awaitCond(seenLatestGossip == Set(second, third))
      }
      enterBarrier("third-joined-second")

      second gossipTo first
      runOn(first, second) {
        // gossip chat will synchronize the views
        awaitMembers(first, second, third)
        awaitMemberStatus(third, Joining)
        awaitMemberStatus(second, Up)
        awaitCond(seenLatestGossip == Set(first, second, third))
      }

      first gossipTo third
      runOn(first, second, third) {
        awaitMembers(first, second, third)
        awaitMemberStatus(first, Up)
        awaitMemberStatus(second, Up)
        awaitMemberStatus(third, Joining)
        awaitCond(seenLatestGossip == Set(first, second, third))
      }

      enterBarrier("convergence-joining-3")

      runOn(leader(first, second, third)) {
        leaderActions()
        awaitMemberStatus(first, Up)
        awaitMemberStatus(second, Up)
        awaitMemberStatus(third, Up)
      }
      enterBarrier("leader-actions-3")

      // leader gossipTo first non-leader
      leader(first, second, third) gossipTo nonLeader(first, second, third).head
      runOn(nonLeader(first, second, third).head) {
        awaitMemberStatus(third, Up)
        awaitCond(seenLatestGossip == Set(leader(first, second, third), myself))
      }

      // first non-leader gossipTo the other non-leader
      nonLeader(first, second, third).head gossipTo nonLeader(first, second, third).tail.head
      runOn(nonLeader(first, second, third).head) {
        // send gossip
        cluster.clusterCore ! InternalClusterAction.SendGossipTo(nonLeader(first, second, third).tail.head)
      }
      runOn(nonLeader(first, second, third).tail.head) {
        awaitMemberStatus(third, Up)
        awaitCond(seenLatestGossip == Set(first, second, third))
      }

      // first non-leader gossipTo the leader
      nonLeader(first, second, third).head gossipTo leader(first, second, third)
      runOn(first, second, third) {
        awaitMemberStatus(first, Up)
        awaitMemberStatus(second, Up)
        awaitMemberStatus(third, Up)
        awaitCond(seenLatestGossip == Set(first, second, third))
      }

      enterBarrier("after-3")
    }

    "perform correct transitions when second becomes unavailble" taggedAs LongRunningTest in {
      runOn(third) {
        markNodeAsUnavailable(second)
        reapUnreachable()
        awaitCond(clusterView.unreachableMembers.contains(Member(second, Up)))
        awaitCond(seenLatestGossip == Set(third))
      }

      enterBarrier("after-second-unavailble")

      third gossipTo first

      runOn(first, third) {
        awaitCond(clusterView.unreachableMembers.contains(Member(second, Up)))
      }

      runOn(first) {
        cluster.down(second)
      }

      enterBarrier("after-second-down")

      first gossipTo third

      runOn(first, third) {
        awaitCond(clusterView.unreachableMembers.contains(Member(second, Down)))
        awaitMemberStatus(second, Down)
        awaitCond(seenLatestGossip == Set(first, third))
      }

      enterBarrier("after-6")
    }

  }
}
