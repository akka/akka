/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import scala.collection.immutable
import scala.collection.SortedSet
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.MemberStatus._
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object MembershipState {
  import MemberStatus._
  private val leaderMemberStatus = Set[MemberStatus](Up, Leaving)
  private val convergenceMemberStatus = Set[MemberStatus](Up, Leaving)
  val convergenceSkipUnreachableWithMemberStatus = Set[MemberStatus](Down, Exiting)
  val removeUnreachableWithMemberStatus = Set[MemberStatus](Down, Exiting)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class MembershipState(latestGossip: Gossip, selfUniqueAddress: UniqueAddress, selfDc: DataCenter) {
  import MembershipState._

  def members: immutable.SortedSet[Member] = latestGossip.members

  def overview: GossipOverview = latestGossip.overview

  def seen(): MembershipState = copy(latestGossip = latestGossip.seen(selfUniqueAddress))

  /**
   * Checks if we have a cluster convergence. If there are any in data center node pairs that cannot reach each other
   * then we can't have a convergence until those nodes reach each other again or one of them is downed
   *
   * @return true if convergence have been reached and false if not
   */
  def convergence(exitingConfirmed: Set[UniqueAddress]): Boolean = {

    // If another member in the data center that is UP or LEAVING and has not seen this gossip or is exiting
    // convergence cannot be reached
    def memberHinderingConvergenceExists =
      members.exists(member ⇒
        member.dataCenter == selfDc &&
          convergenceMemberStatus(member.status) &&
          !(latestGossip.seenByNode(member.uniqueAddress) || exitingConfirmed(member.uniqueAddress)))

    // Find cluster members in the data center that are unreachable from other members of the data center
    // excluding observations from members outside of the data center, that have status DOWN or is passed in as confirmed exiting.
    val unreachableInDc = dcReachabilityExcludingDownedObservers.allUnreachableOrTerminated.collect {
      case node if node != selfUniqueAddress && !exitingConfirmed(node) ⇒ latestGossip.member(node)
    }
    // unreachables outside of the data center or with status DOWN or EXITING does not affect convergence
    val allUnreachablesCanBeIgnored =
      unreachableInDc.forall(unreachable ⇒ convergenceSkipUnreachableWithMemberStatus(unreachable.status))

    allUnreachablesCanBeIgnored && !memberHinderingConvergenceExists
  }

  /**
   * @return Reachability excluding observations from nodes outside of the data center, but including observed unreachable
   *         nodes outside of the data center
   */
  lazy val dcReachability: Reachability =
    overview.reachability.removeObservers(
      members.collect { case m if m.dataCenter != selfDc ⇒ m.uniqueAddress })

  /**
   * @return reachability for data center nodes, with observations from outside the data center or from downed nodes filtered out
   */
  lazy val dcReachabilityExcludingDownedObservers: Reachability = {
    val membersToExclude = members.collect { case m if m.status == Down || m.dataCenter != selfDc ⇒ m.uniqueAddress }
    overview.reachability.removeObservers(membersToExclude).remove(members.collect { case m if m.dataCenter != selfDc ⇒ m.uniqueAddress })
  }

  /**
   * @return true if toAddress should be reachable from the fromDc in general, within a data center
   *         this means only caring about data center local observations, across data centers it
   *         means caring about all observations for the toAddress.
   */
  def isReachableExcludingDownedObservers(toAddress: UniqueAddress): Boolean =
    if (!latestGossip.hasMember(toAddress)) false
    else {
      val to = latestGossip.member(toAddress)

      // if member is in the same data center, we ignore cross data center unreachability
      if (selfDc == to.dataCenter) dcReachabilityExcludingDownedObservers.isReachable(toAddress)
      // if not it is enough that any non-downed node observed it as unreachable
      else latestGossip.reachabilityExcludingDownedObservers.isReachable(toAddress)
    }

  def dcMembers: SortedSet[Member] =
    members.filter(_.dataCenter == selfDc)

  def isLeader(node: UniqueAddress): Boolean =
    leader.contains(node)

  def leader: Option[UniqueAddress] =
    leaderOf(members)

  def roleLeader(role: String): Option[UniqueAddress] =
    leaderOf(members.filter(_.hasRole(role)))

  def leaderOf(mbrs: immutable.SortedSet[Member]): Option[UniqueAddress] = {
    val reachability = dcReachability

    val reachableMembersInDc =
      if (reachability.isAllReachable) mbrs.filter(m ⇒ m.dataCenter == selfDc && m.status != Down)
      else mbrs.filter(m ⇒
        m.dataCenter == selfDc &&
          m.status != Down &&
          (reachability.isReachable(m.uniqueAddress) || m.uniqueAddress == selfUniqueAddress))
    if (reachableMembersInDc.isEmpty) None
    else reachableMembersInDc.find(m ⇒ leaderMemberStatus(m.status))
      .orElse(Some(reachableMembersInDc.min(Member.leaderStatusOrdering)))
      .map(_.uniqueAddress)
  }

}
