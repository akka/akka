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
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("akka.cluster.periodic-tasks-initial-delay = 300 s # turn off all periodic tasks")).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class TransitionMultiJvmNode1 extends TransitionSpec with FailureDetectorPuppetStrategy
class TransitionMultiJvmNode2 extends TransitionSpec with FailureDetectorPuppetStrategy
class TransitionMultiJvmNode3 extends TransitionSpec with FailureDetectorPuppetStrategy
class TransitionMultiJvmNode4 extends TransitionSpec with FailureDetectorPuppetStrategy
class TransitionMultiJvmNode5 extends TransitionSpec with FailureDetectorPuppetStrategy

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
        enterBarrier("after-gossip-" + gossipBarrierCounter)
      }
      runOn(fromRole) {
        enterBarrier("before-gossip-" + gossipBarrierCounter)
        cluster.gossipTo(toRole) // send gossip
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
      runOn(first) {
        awaitMembers(first, second)
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Joining)
        seenLatestGossip must be(Set(first))
        cluster.convergence.isDefined must be(false)
      }
      enterBarrier("second-joined")

      first gossipTo second
      second gossipTo first

      runOn(first, second) {
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Joining)
        seenLatestGossip must be(Set(first, second))
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
      runOn(nonLeader(first, second).head) {
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
        seenLatestGossip must be(Set(first, second))
        cluster.convergence.isDefined must be(true)
      }

      nonLeader(first, second).head gossipTo leader(first, second)
      runOn(first, second) {
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
        seenLatestGossip must be(Set(first, second))
        cluster.convergence.isDefined must be(true)
      }

      enterBarrier("after-2")
    }

    "perform correct transitions when third joins second" taggedAs LongRunningTest in {

      runOn(third) {
        cluster.join(second)
      }
      runOn(second) {
        awaitMembers(first, second, third)
        cluster.convergence.isDefined must be(false)
        memberStatus(third) must be(Joining)
        seenLatestGossip must be(Set(second))
      }
      enterBarrier("third-joined-second")

      second gossipTo first
      runOn(first) {
        members must be(Set(first, second, third))
        memberStatus(third) must be(Joining)
        seenLatestGossip must be(Set(first, second))
        cluster.convergence.isDefined must be(false)
      }

      first gossipTo third
      third gossipTo first
      third gossipTo second
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

      // and back again
      nonLeader(first, second, third).tail.head gossipTo nonLeader(first, second, third).head
      runOn(nonLeader(first, second, third).head) {
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

    "startup a second separated cluster consisting of nodes fourth and fifth" taggedAs LongRunningTest in {
      runOn(fifth) {
        startClusterNode()
        cluster.leaderActions()
        cluster.status must be(Up)
      }
      enterBarrier("fifth-started")

      runOn(fourth) {
        cluster.join(fifth)
      }
      runOn(fifth) {
        awaitMembers(fourth, fifth)
      }
      enterBarrier("fourth-joined")

      fifth gossipTo fourth
      fourth gossipTo fifth

      runOn(fourth, fifth) {
        memberStatus(fourth) must be(Joining)
        memberStatus(fifth) must be(Up)
        seenLatestGossip must be(Set(fourth, fifth))
        cluster.convergence.isDefined must be(true)
      }

      enterBarrier("after-4")
    }

    "perform correct transitions when second cluster (node fourth) joins first cluster (node third)" taggedAs LongRunningTest in {

      runOn(fourth) {
        cluster.join(third)
      }
      runOn(third) {
        awaitMembers(first, second, third, fourth)
        seenLatestGossip must be(Set(third))
      }
      enterBarrier("fourth-joined-third")

      third gossipTo second
      runOn(second) {
        seenLatestGossip must be(Set(second, third))
      }

      second gossipTo fourth
      runOn(fourth) {
        members must be(roles.toSet)
        // merge conflict
        seenLatestGossip must be(Set(fourth))
      }

      fourth gossipTo first
      fourth gossipTo second
      fourth gossipTo third
      fourth gossipTo fifth
      runOn(first, second, third, fifth) {
        members must be(roles.toSet)
        seenLatestGossip must be(Set(fourth, myself))
      }

      first gossipTo fifth
      runOn(fifth) {
        seenLatestGossip must be(Set(first, fourth, fifth))
      }

      fifth gossipTo third
      runOn(third) {
        seenLatestGossip must be(Set(first, third, fourth, fifth))
      }

      third gossipTo second
      runOn(second) {
        seenLatestGossip must be(roles.toSet)
        cluster.convergence.isDefined must be(true)
      }

      second gossipTo first
      second gossipTo third
      second gossipTo fourth
      third gossipTo fifth

      seenLatestGossip must be(roles.toSet)
      memberStatus(first) must be(Up)
      memberStatus(second) must be(Up)
      memberStatus(third) must be(Up)
      memberStatus(fourth) must be(Joining)
      memberStatus(fifth) must be(Up)
      cluster.convergence.isDefined must be(true)

      enterBarrier("convergence-joining-3")

      runOn(leader(roles: _*)) {
        cluster.leaderActions()
        memberStatus(fourth) must be(Up)
        seenLatestGossip must be(Set(myself))
        cluster.convergence.isDefined must be(false)
      }
      // spread the word
      for (x :: y :: Nil ← (roles.sorted ++ roles.sorted.dropRight(1)).toList.sliding(2)) {
        x gossipTo y
      }

      enterBarrier("spread-5")

      seenLatestGossip must be(roles.toSet)
      memberStatus(first) must be(Up)
      memberStatus(second) must be(Up)
      memberStatus(third) must be(Up)
      memberStatus(fourth) must be(Up)
      memberStatus(fifth) must be(Up)
      cluster.convergence.isDefined must be(true)

      enterBarrier("after-5")
    }

    "perform correct transitions when second becomes unavailble" taggedAs LongRunningTest in {
      runOn(fifth) {
        markNodeAsUnavailable(second)
        cluster.reapUnreachableMembers()
        cluster.latestGossip.overview.unreachable must contain(Member(second, Up))
        seenLatestGossip must be(Set(fifth))
      }

      enterBarrier("after-second-unavailble")

      // spread the word
      val gossipRound = List(fifth, fourth, third, first, third, fourth, fifth)
      for (x :: y :: Nil ← gossipRound.sliding(2)) {
        x gossipTo y
      }

      runOn((roles.filterNot(_ == second)): _*) {
        cluster.latestGossip.overview.unreachable must contain(Member(second, Up))
        cluster.convergence.isDefined must be(false)
      }

      runOn(third) {
        cluster.down(second)
        awaitMemberStatus(second, Down)
      }

      enterBarrier("after-second-down")

      // spread the word
      val gossipRound2 = List(third, fourth, fifth, first, third, fourth, fifth)
      for (x :: y :: Nil ← gossipRound2.sliding(2)) {
        x gossipTo y
      }

      runOn((roles.filterNot(_ == second)): _*) {
        cluster.latestGossip.overview.unreachable must contain(Member(second, Down))
        memberStatus(second) must be(Down)
        seenLatestGossip must be(Set(first, third, fourth, fifth))
        cluster.convergence.isDefined must be(true)
      }

      enterBarrier("after-6")
    }

  }
}
