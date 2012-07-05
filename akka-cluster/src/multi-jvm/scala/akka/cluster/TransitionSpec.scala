/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.Address
import akka.remote.testconductor.RoleName
import MemberStatus._

object TransitionMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.periodic-tasks-initial-delay = 300 s # turn off all periodic tasks")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class TransitionMultiJvmNode1 extends TransitionSpec with FailureDetectorPuppetStrategy
class TransitionMultiJvmNode2 extends TransitionSpec with FailureDetectorPuppetStrategy
class TransitionMultiJvmNode3 extends TransitionSpec with FailureDetectorPuppetStrategy

abstract class TransitionSpec
  extends MultiNodeSpec(TransitionMultiJvmSpec)
  with MultiNodeClusterSpec {

  import TransitionMultiJvmSpec._

  // sorted in the order used by the cluster
  def leader(roles: RoleName*) = roles.sorted.head
  def nonLeader(roles: RoleName*) = roles.toSeq.sorted.tail

  def memberStatus(address: Address): MemberStatus = {
    val statusOption = (cluster.latestGossip.members ++ cluster.latestGossip.overview.unreachable).collectFirst {
      case m if m.address == address ⇒ m.status
    }
    statusOption must not be (None)
    statusOption.get
  }

  def memberAddresses: Set[Address] = cluster.latestGossip.members.map(_.address)

  def members: Set[RoleName] = memberAddresses.flatMap(roleName(_))

  def seenLatestGossip: Set[RoleName] = {
    val gossip = cluster.latestGossip
    gossip.overview.seen.collect {
      case (address, v) if v == gossip.version ⇒ roleName(address)
    }.flatten.toSet
  }

  def awaitSeen(addresses: Address*): Unit = awaitCond {
    (seenLatestGossip map address) == addresses.toSet
  }

  def awaitMembers(addresses: Address*): Unit = awaitCond {
    memberAddresses == addresses.toSet
  }

  def awaitMemberStatus(address: Address, status: MemberStatus): Unit = awaitCond {
    memberStatus(address) == status
  }

  // DSL sugar for `role1 gossipTo role2`
  implicit def roleExtras(role: RoleName): RoleWrapper = new RoleWrapper(role)
  var gossipBarrierCounter = 0
  class RoleWrapper(fromRole: RoleName) {
    def gossipTo(toRole: RoleName): Unit = {
      gossipBarrierCounter += 1
      runOn(toRole) {
        val g = cluster.latestGossip
        enterBarrier("before-gossip-" + gossipBarrierCounter)
        awaitCond(cluster.latestGossip != g) // received gossip
        // gossip chat will synchronize the views
        awaitCond((Set(fromRole, toRole) -- seenLatestGossip).isEmpty)
        enterBarrier("after-gossip-" + gossipBarrierCounter)
      }
      runOn(fromRole) {
        enterBarrier("before-gossip-" + gossipBarrierCounter)
        cluster.gossipTo(toRole) // send gossip
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
        startClusterNode()
        cluster.isSingletonCluster must be(true)
        cluster.status must be(Joining)
        cluster.convergence.isDefined must be(true)
        cluster.leaderActions()
        cluster.status must be(Up)
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
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Joining)
        awaitCond(seenLatestGossip == Set(first, second))
        cluster.convergence.isDefined must be(true)
      }
      enterBarrier("convergence-joining-2")

      runOn(leader(first, second)) {
        cluster.leaderActions()
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
      }
      enterBarrier("leader-actions-2")

      leader(first, second) gossipTo nonLeader(first, second).head
      runOn(first, second) {
        // gossip chat will synchronize the views
        awaitCond(memberStatus(second) == Up)
        seenLatestGossip must be(Set(first, second))
        memberStatus(first) must be(Up)
        cluster.convergence.isDefined must be(true)
      }

      enterBarrier("after-2")
    }

    "perform correct transitions when third joins second" taggedAs LongRunningTest in {

      runOn(third) {
        cluster.join(second)
      }
      runOn(second, third) {
        // gossip chat from the join will synchronize the views
        awaitMembers(first, second, third)
        memberStatus(third) must be(Joining)
        awaitCond(seenLatestGossip == Set(second, third))
        cluster.convergence.isDefined must be(false)
      }
      enterBarrier("third-joined-second")

      second gossipTo first
      runOn(first, second) {
        // gossip chat will synchronize the views
        awaitMembers(first, second, third)
        memberStatus(third) must be(Joining)
        awaitCond(memberStatus(second) == Up)
        seenLatestGossip must be(Set(first, second, third))
        cluster.convergence.isDefined must be(true)
      }

      first gossipTo third
      runOn(first, second, third) {
        members must be(Set(first, second, third))
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
        memberStatus(third) must be(Joining)
        seenLatestGossip must be(Set(first, second, third))
        cluster.convergence.isDefined must be(true)
      }

      enterBarrier("convergence-joining-3")

      runOn(leader(first, second, third)) {
        cluster.leaderActions()
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
        memberStatus(third) must be(Up)
      }
      enterBarrier("leader-actions-3")

      // leader gossipTo first non-leader
      leader(first, second, third) gossipTo nonLeader(first, second, third).head
      runOn(nonLeader(first, second, third).head) {
        memberStatus(third) must be(Up)
        seenLatestGossip must be(Set(leader(first, second, third), myself))
        cluster.convergence.isDefined must be(false)
      }

      // first non-leader gossipTo the other non-leader
      nonLeader(first, second, third).head gossipTo nonLeader(first, second, third).tail.head
      runOn(nonLeader(first, second, third).head) {
        cluster.gossipTo(nonLeader(first, second, third).tail.head)
      }
      runOn(nonLeader(first, second, third).tail.head) {
        memberStatus(third) must be(Up)
        seenLatestGossip must be(Set(first, second, third))
        cluster.convergence.isDefined must be(true)
      }

      // first non-leader gossipTo the leader
      nonLeader(first, second, third).head gossipTo leader(first, second, third)
      runOn(first, second, third) {
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
        memberStatus(third) must be(Up)
        seenLatestGossip must be(Set(first, second, third))
        cluster.convergence.isDefined must be(true)
      }

      enterBarrier("after-3")
    }

    "perform correct transitions when second becomes unavailble" taggedAs LongRunningTest in {
      runOn(third) {
        markNodeAsUnavailable(second)
        cluster.reapUnreachableMembers()
        cluster.latestGossip.overview.unreachable must contain(Member(second, Up))
        seenLatestGossip must be(Set(third))
      }

      enterBarrier("after-second-unavailble")

      third gossipTo first

      runOn(first, third) {
        cluster.latestGossip.overview.unreachable must contain(Member(second, Up))
        cluster.convergence.isDefined must be(false)
      }

      runOn(first) {
        cluster.down(second)
        awaitMemberStatus(second, Down)
      }

      enterBarrier("after-second-down")

      first gossipTo third

      runOn(first, third) {
        cluster.latestGossip.overview.unreachable must contain(Member(second, Down))
        memberStatus(second) must be(Down)
        seenLatestGossip must be(Set(first, third))
        cluster.convergence.isDefined must be(true)
      }

      enterBarrier("after-6")
    }

  }
}
