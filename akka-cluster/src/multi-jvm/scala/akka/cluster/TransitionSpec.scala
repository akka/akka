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
    withFallback(ConfigFactory.parseString(
      "akka.cluster.periodic-tasks-initial-delay = 300 s # turn off all periodic tasks")).
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
    seenLatestGossip.map(node(_).address) == addresses.toSet
  }

  def awaitMembers(addresses: Address*): Unit = awaitCond {
    memberAddresses == addresses.toSet
  }

  def awaitMemberStatus(address: Address, status: MemberStatus): Unit = awaitCond {
    memberStatus(address) == Up
  }

  // implicit conversion from RoleName to Address
  implicit def role2Address(role: RoleName): Address = node(role).address

  // DSL sugar for `role1 gossipTo role2`
  implicit def roleExtras(role: RoleName): RoleWrapper = new RoleWrapper(role)
  var gossipBarrierCounter = 0
  class RoleWrapper(fromRole: RoleName) {
    def gossipTo(toRole: RoleName): Unit = {
      gossipBarrierCounter += 1
      runOn(toRole) {
        val g = cluster.latestGossip
        testConductor.enter("before-gossip-" + gossipBarrierCounter)
        awaitCond(cluster.latestGossip != g) // received gossip
        testConductor.enter("after-gossip-" + gossipBarrierCounter)
      }
      runOn(fromRole) {
        testConductor.enter("before-gossip-" + gossipBarrierCounter)
        cluster.gossipTo(node(toRole).address) // send gossip
        testConductor.enter("after-gossip-" + gossipBarrierCounter)
      }
      runOn(roles.filterNot(r ⇒ r == fromRole || r == toRole): _*) {
        testConductor.enter("before-gossip-" + gossipBarrierCounter)
        testConductor.enter("after-gossip-" + gossipBarrierCounter)
      }
    }
  }

  "A Cluster" must {

    "start nodes as singleton clusters" taggedAs LongRunningTest in {

      startClusterNode()
      cluster.isSingletonCluster must be(true)
      cluster.status must be(Joining)
      cluster.convergence.isDefined must be(true)
      cluster.leaderActions()
      cluster.status must be(Up)

      testConductor.enter("after-1")
    }

    "perform correct transitions when second joining first" taggedAs LongRunningTest in {

      runOn(second) {
        cluster.join(first)
      }
      runOn(first) {
        awaitMembers(first, second)
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Joining)
        cluster.convergence.isDefined must be(false)
      }
      testConductor.enter("second-joined")

      first gossipTo second
      runOn(second) {
        members must be(Set(first, second))
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Joining)
        // we got a conflicting version in second, and therefore not convergence in second
        seenLatestGossip must be(Set(second))
        cluster.convergence.isDefined must be(false)
      }

      second gossipTo first
      runOn(first) {
        seenLatestGossip must be(Set(first, second))
      }

      first gossipTo second
      runOn(second) {
        seenLatestGossip must be(Set(first, second))
      }

      runOn(first, second) {
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Joining)
        cluster.convergence.isDefined must be(true)
      }
      testConductor.enter("convergence-joining-2")

      runOn(leader(first, second)) {
        cluster.leaderActions()
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
      }
      testConductor.enter("leader-actions-2")

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

      testConductor.enter("after-2")
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
      testConductor.enter("third-joined-second")

      second gossipTo first
      runOn(first) {
        members must be(Set(first, second, third))
        cluster.convergence.isDefined must be(false)
        memberStatus(third) must be(Joining)
      }

      first gossipTo third
      runOn(third) {
        members must be(Set(first, second, third))
        cluster.convergence.isDefined must be(false)
        memberStatus(third) must be(Joining)
        // conflicting version
        seenLatestGossip must be(Set(third))
      }

      third gossipTo first
      third gossipTo second
      runOn(first, second) {
        seenLatestGossip must be(Set(myself, third))
      }

      first gossipTo second
      runOn(second) {
        seenLatestGossip must be(Set(first, second, third))
        cluster.convergence.isDefined must be(true)
      }

      runOn(first, third) {
        cluster.convergence.isDefined must be(false)
      }

      second gossipTo first
      second gossipTo third
      runOn(first, second, third) {
        seenLatestGossip must be(Set(first, second, third))
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
        memberStatus(third) must be(Joining)
        cluster.convergence.isDefined must be(true)
      }

      testConductor.enter("convergence-joining-3")

      runOn(leader(first, second, third)) {
        cluster.leaderActions()
        memberStatus(first) must be(Up)
        memberStatus(second) must be(Up)
        memberStatus(third) must be(Up)
      }
      testConductor.enter("leader-actions-3")

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
        cluster.gossipTo(node(nonLeader(first, second, third).tail.head).address)
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

      testConductor.enter("after-3")
    }

    "startup a second separated cluster consisting of nodes fourth and fifth" taggedAs LongRunningTest in {
      runOn(fourth) {
        cluster.join(fifth)
        awaitMembers(fourth, fifth)
        cluster.gossipTo(fifth)
        awaitSeen(fourth, fifth)
        cluster.convergence.isDefined must be(true)
      }
      runOn(fifth) {
        awaitMembers(fourth, fifth)
        cluster.gossipTo(fourth)
        awaitSeen(fourth, fifth)
        cluster.gossipTo(fourth)
        cluster.convergence.isDefined must be(true)
      }
      testConductor.enter("fourth-joined-fifth")

      testConductor.enter("after-4")
    }

    "perform correct transitions when second cluster (node fourth) joins first cluster (node third)" taggedAs LongRunningTest in {

      runOn(fourth) {
        cluster.join(third)
      }
      runOn(third) {
        awaitMembers(first, second, third, fourth)
        seenLatestGossip must be(Set(third))
      }
      testConductor.enter("fourth-joined-third")

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

      testConductor.enter("convergence-joining-3")

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

      testConductor.enter("spread-5")

      seenLatestGossip must be(roles.toSet)
      memberStatus(first) must be(Up)
      memberStatus(second) must be(Up)
      memberStatus(third) must be(Up)
      memberStatus(fourth) must be(Up)
      memberStatus(fifth) must be(Up)
      cluster.convergence.isDefined must be(true)

      testConductor.enter("after-5")
    }

    "perform correct transitions when second becomes unavailble" taggedAs LongRunningTest in {
      runOn(fifth) {
        markNodeAsUnavailable(second)
        cluster.reapUnreachableMembers()
        cluster.latestGossip.overview.unreachable must contain(Member(second, Up))
        seenLatestGossip must be(Set(fifth))
      }

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

      testConductor.enter("after-6")
    }

  }
}
