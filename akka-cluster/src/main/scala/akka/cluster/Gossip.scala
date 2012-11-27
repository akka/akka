/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.Address
import scala.collection.immutable.SortedSet
import MemberStatus._

/**
 * Internal API
 */
private[cluster] object Gossip {
  val emptyMembers: SortedSet[Member] = SortedSet.empty
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
 * same history. When merged the seen table is cleared.
 *
 * When a node is told by the user to leave the cluster the leader will move it to `Leaving`
 * and then rebalance and repartition the cluster and start hand-off by migrating the actors
 * from the leaving node to the new partitions. Once this process is complete the leader will
 * move the node to the `Exiting` state and once a convergence is complete move the node to
 * `Removed` by removing it from the `members` set and sending a `Removed` command to the
 * removed node telling it to shut itself down.
 */
private[cluster] case class Gossip(
  overview: GossipOverview = GossipOverview(),
  members: SortedSet[Member] = Gossip.emptyMembers, // sorted set of members with their status, sorted by address
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

    val allowedLiveMemberStatuses: Set[MemberStatus] = Set(Joining, Up, Leaving, Exiting)
    def hasNotAllowedLiveMemberStatus(m: Member) = !allowedLiveMemberStatuses.contains(m.status)
    if (members exists hasNotAllowedLiveMemberStatus)
      throw new IllegalArgumentException("Live members must have status [%s], got [%s]"
        format (allowedLiveMemberStatuses.mkString(", "),
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
    if (overview.seen.contains(address) && overview.seen(address) == version) this
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

    // 4. fresh seen table
    val mergedSeen = Map.empty[Address, VectorClock]

    Gossip(GossipOverview(mergedSeen, mergedUnreachable), mergedMembers, mergedVClock)
  }

  /**
   * Checks if we have a cluster convergence. If there are any unreachable nodes then we can't have a convergence -
   * waiting for user to act (issuing DOWN) or leader to act (issuing DOWN through auto-down).
   *
   * @return true if convergence have been reached and false if not
   */
  def convergence: Boolean = {
    val unreachable = overview.unreachable
    val seen = overview.seen

    // First check that:
    //   1. we don't have any members that are unreachable, or
    //   2. all unreachable members in the set have status DOWN
    // Else we can't continue to check for convergence
    // When that is done we check that all the entries in the 'seen' table have the same vector clock version
    // and that all members exists in seen table
    val hasUnreachable = unreachable.nonEmpty && unreachable.exists { _.status != Down }
    def allMembersInSeen = members.forall(m ⇒ seen.contains(m.address))

    def seenSame: Boolean =
      if (seen.isEmpty) {
        // if both seen and members are empty, then every(no)body has seen the same thing
        members.isEmpty
      } else {
        val values = seen.values
        val seenHead = values.head
        values.forall(_ == seenHead)
      }

    !hasUnreachable && allMembersInSeen && seenSame
  }

  def isLeader(address: Address): Boolean = leader == Some(address)

  def leader: Option[Address] = members.find(_.status != Exiting).orElse(members.headOption).map(_.address)

  def isSingletonCluster: Boolean = members.size == 1

  /**
   * Returns true if the node is UP or JOINING.
   */
  def isAvailable(address: Address): Boolean = !isUnavailable(address)

  def isUnavailable(address: Address): Boolean = {
    val isUnreachable = overview.unreachable exists { _.address == address }
    val hasUnavailableMemberStatus = members exists { m ⇒ m.status.isUnavailable && m.address == address }
    isUnreachable || hasUnavailableMemberStatus
  }

  def member(address: Address): Member = {
    members.find(_.address == address).orElse(overview.unreachable.find(_.address == address)).
      getOrElse(Member(address, Removed))
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

/**
 * INTERNAL API
 * When conflicting versions of received and local [[akka.cluster.Gossip]] is detected
 * it's forwarded to the leader for conflict resolution.
 */
private[cluster] case class GossipMergeConflict(a: GossipEnvelope, b: GossipEnvelope) extends ClusterMessage

