/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.Address
import scala.collection.immutable
import MemberStatus._

/**
 * INTERNAL API
 */
private[cluster] object Gossip {
  val emptyMembers: immutable.SortedSet[Member] = immutable.SortedSet.empty
  val empty: Gossip = new Gossip(Gossip.emptyMembers)

  def apply(members: immutable.SortedSet[Member]) =
    if (members.isEmpty) empty else empty.copy(members = members)

  private val leaderMemberStatus = Set[MemberStatus](Up, Leaving)
  private val convergenceMemberStatus = Set[MemberStatus](Up, Leaving, Exiting)
}

/**
 * INTERNAL API
 *
 * Represents the state of the cluster; cluster ring membership, ring convergence -
 * all versioned by a vector clock.
 *
 * When a node is joining the `Member`, with status `Joining`, is added to `members`.
 * If the joining node was downed it is moved from `overview.unreachable` (status `Down`)
 * to `members` (status `Joining`). It cannot rejoin if not first downed.
 *
 * When convergence is reached the leader change status of `members` from `Joining`
 * to `Up`.
 *
 * When failure detector consider a node as unavailable it will be moved from
 * `members` to `overview.unreachable`.
 *
 * When a node is downed, either manually or automatically, its status is changed to `Down`.
 * It is also removed from `overview.seen` table. The node will reside as `Down` in the
 * `overview.unreachable` set until joining again and it will then go through the normal
 * joining procedure.
 *
 * When a `Gossip` is received the version (vector clock) is used to determine if the
 * received `Gossip` is newer or older than the current local `Gossip`. The received `Gossip`
 * and local `Gossip` is merged in case of conflicting version, i.e. vector clocks without
 * same history.
 *
 * When a node is told by the user to leave the cluster the leader will move it to `Leaving`
 * and then rebalance and repartition the cluster and start hand-off by migrating the actors
 * from the leaving node to the new partitions. Once this process is complete the leader will
 * move the node to the `Exiting` state and once a convergence is complete move the node to
 * `Removed` by removing it from the `members` set and sending a `Removed` command to the
 * removed node telling it to shut itself down.
 */
private[cluster] case class Gossip(
  members: immutable.SortedSet[Member], // sorted set of members with their status, sorted by address
  overview: GossipOverview = GossipOverview(),
  version: VectorClock = VectorClock()) // vector clock version
  extends ClusterMessage // is a serializable cluster message
  with Versioned[Gossip] {

  // FIXME can be disabled as optimization
  assertInvariants

  private def assertInvariants: Unit = {
    val unreachableAndLive = members.intersect(overview.unreachable)
    if (unreachableAndLive.nonEmpty)
      throw new IllegalArgumentException("Same nodes in both members and unreachable is not allowed, got [%s]"
        format unreachableAndLive.mkString(", "))

    val allowedLiveMemberStatus: Set[MemberStatus] = Set(Joining, Up, Leaving, Exiting)
    def hasNotAllowedLiveMemberStatus(m: Member) = !allowedLiveMemberStatus(m.status)
    if (members exists hasNotAllowedLiveMemberStatus)
      throw new IllegalArgumentException("Live members must have status [%s], got [%s]"
        format (allowedLiveMemberStatus.mkString(", "),
          (members filter hasNotAllowedLiveMemberStatus).mkString(", ")))

    val seenButNotMember = overview.seen.keySet -- members.map(_.address) -- overview.unreachable.map(_.address)
    if (seenButNotMember.nonEmpty)
      throw new IllegalArgumentException("Nodes not part of cluster have marked the Gossip as seen, got [%s]"
        format seenButNotMember.mkString(", "))

  }

  /**
   * Increments the version for this 'Node'.
   */
  def :+(node: VectorClock.Node): Gossip = copy(version = version :+ node)

  /**
   * Adds a member to the member node ring.
   */
  def :+(member: Member): Gossip = {
    if (members contains member) this
    else this copy (members = members + member)
  }

  /**
   * Marks the gossip as seen by this node (address) by updating the address entry in the 'gossip.overview.seen'
   * Map with the VectorClock (version) for the new gossip.
   */
  def seen(address: Address): Gossip = {
    if (seenByAddress(address)) this
    else this copy (overview = overview copy (seen = overview.seen + (address -> version)))
  }

  /**
   * The nodes that have seen current version of the Gossip.
   */
  def seenBy: Set[Address] = {
    overview.seen.collect {
      case (address, vclock) if vclock == version ⇒ address
    }.toSet
  }

  /**
   * Has this Gossip been seen by this address.
   */
  def seenByAddress(address: Address): Boolean = {
    overview.seen.get(address).exists(_ == version)
  }

  private def mergeSeenTables(allowed: Set[Member], one: Map[Address, VectorClock], another: Map[Address, VectorClock]): Map[Address, VectorClock] = {
    (Map.empty[Address, VectorClock] /: allowed) {
      (merged, member) ⇒
        val address = member.address
        (one.get(address), another.get(address)) match {
          case (None, None)     ⇒ merged
          case (Some(v1), None) ⇒ merged.updated(address, v1)
          case (None, Some(v2)) ⇒ merged.updated(address, v2)
          case (Some(v1), Some(v2)) ⇒
            v1 tryCompareTo v2 match {
              case None             ⇒ merged
              case Some(x) if x > 0 ⇒ merged.updated(address, v1)
              case _                ⇒ merged.updated(address, v2)
            }
        }
    }
  }

  /**
   * Merges the seen table of two Gossip instances.
   */
  def mergeSeen(that: Gossip): Gossip =
    this copy (overview = overview copy (seen = mergeSeenTables(members, overview.seen, that.overview.seen)))

  /**
   * Merges two Gossip instances including membership tables, and the VectorClock histories.
   */
  def merge(that: Gossip): Gossip = {
    import Member.ordering

    // 1. merge vector clocks
    val mergedVClock = this.version merge that.version

    // 2. merge unreachable by selecting the single Member with highest MemberStatus out of the Member groups
    val mergedUnreachable = Member.pickHighestPriority(this.overview.unreachable, that.overview.unreachable)

    // 3. merge members by selecting the single Member with highest MemberStatus out of the Member groups,
    //    and exclude unreachable
    val mergedMembers = Gossip.emptyMembers ++ Member.pickHighestPriority(this.members, that.members).filterNot(mergedUnreachable.contains)

    // 4. merge seen table
    val mergedSeen = mergeSeenTables(mergedMembers, overview.seen, that.overview.seen)

    Gossip(mergedMembers, GossipOverview(mergedSeen, mergedUnreachable), mergedVClock)
  }

  /**
   * Checks if we have a cluster convergence. If there are any unreachable nodes then we can't have a convergence -
   * waiting for user to act (issuing DOWN) or leader to act (issuing DOWN through auto-down).
   *
   * @return true if convergence have been reached and false if not
   */
  def convergence: Boolean = {
    // First check that:
    //   1. we don't have any members that are unreachable, or
    //   2. all unreachable members in the set have status DOWN
    // Else we can't continue to check for convergence
    // When that is done we check that all members with a convergence
    // status is in the seen table and has the latest vector clock
    // version
    overview.unreachable.forall(_.status == Down) &&
      !members.exists(m ⇒ Gossip.convergenceMemberStatus(m.status) && !seenByAddress(m.address))
  }

  def isLeader(address: Address): Boolean = leader == Some(address)

  def leader: Option[Address] = leaderOf(members)

  def roleLeader(role: String): Option[Address] = leaderOf(members.filter(_.hasRole(role)))

  private def leaderOf(mbrs: immutable.SortedSet[Member]): Option[Address] = {
    if (mbrs.isEmpty) None
    else mbrs.find(m ⇒ Gossip.leaderMemberStatus(m.status)).
      orElse(Some(mbrs.min(Member.leaderStatusOrdering))).map(_.address)
  }

  def allRoles: Set[String] = members.flatMap(_.roles)

  def isSingletonCluster: Boolean = members.size == 1

  /**
   * Returns true if the node is in the unreachable set
   */
  def isUnreachable(address: Address): Boolean =
    overview.unreachable exists { _.address == address }

  def member(address: Address): Member = {
    members.find(_.address == address).orElse(overview.unreachable.find(_.address == address)).
      getOrElse(Member(address, Removed, Set.empty))
  }

  override def toString =
    "Gossip(" +
      "overview = " + overview +
      ", members = [" + members.mkString(", ") +
      "], version = " + version +
      ")"
}

/**
 * INTERNAL API
 * Represents the overview of the cluster, holds the cluster convergence table and set with unreachable nodes.
 */
private[cluster] case class GossipOverview(
  seen: Map[Address, VectorClock] = Map.empty,
  unreachable: Set[Member] = Set.empty) {

  def isNonDownUnreachable(address: Address): Boolean =
    unreachable.exists { m ⇒ m.address == address && m.status != Down }

  override def toString =
    "GossipOverview(seen = [" + seen.mkString(", ") +
      "], unreachable = [" + unreachable.mkString(", ") +
      "])"
}

/**
 * INTERNAL API
 * Envelope adding a sender address to the gossip.
 */
private[cluster] case class GossipEnvelope(from: Address, gossip: Gossip, conversation: Boolean = true) extends ClusterMessage
