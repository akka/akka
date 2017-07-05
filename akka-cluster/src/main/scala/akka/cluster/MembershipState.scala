/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import java.util.concurrent.ThreadLocalRandom

import scala.collection.immutable
import scala.collection.SortedSet
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.MemberStatus._
import akka.annotation.InternalApi

import scala.annotation.tailrec
import scala.collection.breakOut
import scala.util.Random

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
   * @return All members grouped by dc and sorted by age
   */
  lazy val ageSortedNodesPerDc: Map[DataCenter, SortedSet[Member]] =
    latestGossip.members.foldLeft(Map.empty[DataCenter, SortedSet[Member]]) { (acc, member) ⇒
      acc.get(member.dataCenter) match {
        case Some(set) ⇒ acc + (member.dataCenter → (set + member))
        case None      ⇒ acc + (member.dataCenter → (SortedSet.empty(Member.ageOrdering) + member))
      }
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

  def validNodeForGossip(node: UniqueAddress): Boolean =
    node != selfUniqueAddress && isReachableExcludingDownedObservers(node)

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class GossipTargetSelector(val crossDcConnections: Int, val reduceGossipDifferentViewProbability: Double) {

  final def gossipTarget(state: MembershipState): Option[UniqueAddress] = {
    selectRandomNode(gossipTargets(state))
  }

  final def gossipTargets(state: MembershipState): Vector[UniqueAddress] =
    if (state.latestGossip.isMultiDc) multiDcGossipTargets(state)
    else localDcGossipTargets(state)

  /**
   * Chooses a set of possible gossip targets that is in the same dc. If the cluster is not multi dc this will
   * means it is a choice among all nodes of the cluster.
   */
  protected def localDcGossipTargets(state: MembershipState): Vector[UniqueAddress] = {
    val latestGossip = state.latestGossip
    val firstSelection: Vector[UniqueAddress] =
      if (preferNodesWithDifferentView(state)) {
        // If it's time to try to gossip to some nodes with a different view
        // gossip to a random alive same dc member with preference to a member with older gossip version
        latestGossip.members.collect {
          case m if m.dataCenter == state.selfDc && !latestGossip.seenByNode(m.uniqueAddress) && state.validNodeForGossip(m.uniqueAddress) ⇒
            m.uniqueAddress
        }(breakOut)
      } else Vector.empty

    // Fall back to localGossip
    if (firstSelection.isEmpty) {
      latestGossip.members.toVector.collect {
        case m if m.dataCenter == state.selfDc && state.validNodeForGossip(m.uniqueAddress) ⇒ m.uniqueAddress
      }
    } else firstSelection

  }

  /**
   * Choose cross-dc nodes if this one of the N oldest nodes, and if not fall back to gosip locally in the dc
   */
  protected def multiDcGossipTargets(state: MembershipState): Vector[UniqueAddress] = {
    val latestGossip = state.latestGossip
    // 20% of the times across dcs and 80% local (doing it for all nodes to avoid having to collect the oldest nodes per
    // dc all the time, when #23290 is merged we can maybe cache oldest members per dc in that)
    if (selectDcLocalNodes) localDcGossipTargets(state)
    else {
      val nodesPerDc = state.ageSortedNodesPerDc

      // only do cross DC gossip if this node is among the N oldest
      val selfMember = state.latestGossip.member(state.selfUniqueAddress)
      if (!nodesPerDc(state.selfDc).contains(selfMember)) localDcGossipTargets(state)
      else {
        @tailrec
        def findFirstDcWithValidNodes(left: List[DataCenter]): Vector[UniqueAddress] =
          left match {
            case dc :: tail ⇒

              val validNodes = nodesPerDc(dc).collect {
                case member if state.validNodeForGossip(member.uniqueAddress) ⇒
                  member.uniqueAddress
              }.take(crossDcConnections)

              if (validNodes.nonEmpty) validNodes.toVector
              else findFirstDcWithValidNodes(tail) // no valid nodes in dc, try next

            case Nil ⇒
              Vector.empty
          }

        // chose another DC at random
        val otherDcsInRandomOrder = dcsInRandomOrder((nodesPerDc - state.selfDc).keys.toList)
        val nodes = findFirstDcWithValidNodes(otherDcsInRandomOrder)
        if (nodes.nonEmpty) nodes
        // no other dc with reachable nodes, fall back to local gossip
        else localDcGossipTargets(state)
      }
    }
  }

  /**
   * For large clusters we should avoid shooting down individual
   * nodes. Therefore the probability is reduced for large clusters.
   */
  protected def adjustedGossipDifferentViewProbability(clusterSize: Int): Double = {
    val low = reduceGossipDifferentViewProbability
    val high = low * 3
    // start reduction when cluster is larger than configured ReduceGossipDifferentViewProbability
    if (clusterSize <= low)
      reduceGossipDifferentViewProbability
    else {
      // don't go lower than 1/10 of the configured GossipDifferentViewProbability
      val minP = reduceGossipDifferentViewProbability / 10
      if (clusterSize >= high)
        minP
      else {
        // linear reduction of the probability with increasing number of nodes
        // from ReduceGossipDifferentViewProbability at ReduceGossipDifferentViewProbability nodes
        // to ReduceGossipDifferentViewProbability / 10 at ReduceGossipDifferentViewProbability * 3 nodes
        // i.e. default from 0.8 at 400 nodes, to 0.08 at 1600 nodes
        val k = (minP - reduceGossipDifferentViewProbability) / (high - low)
        reduceGossipDifferentViewProbability + (clusterSize - low) * k
      }
    }
  }

  protected def selectDcLocalNodes: Boolean = ThreadLocalRandom.current.nextDouble() > 0.2D

  protected def preferNodesWithDifferentView(state: MembershipState): Boolean =
    ThreadLocalRandom.current.nextDouble() < adjustedGossipDifferentViewProbability(state.latestGossip.members.size)

  protected def dcsInRandomOrder(dcs: List[DataCenter]): List[DataCenter] =
    Random.shuffle(dcs)

  protected def selectRandomNode(nodes: IndexedSeq[UniqueAddress]): Option[UniqueAddress] =
    if (nodes.isEmpty) None
    else Some(nodes(ThreadLocalRandom.current.nextInt(nodes.size)))
}
