/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import scala.collection.immutable
import MemberStatus._
import scala.concurrent.duration.Deadline

/**
 * INTERNAL API
 */
private[cluster] object Gossip {
  val emptyMembers: immutable.SortedSet[Member] = immutable.SortedSet.empty
  val empty: Gossip = new Gossip(Gossip.emptyMembers)

  def apply(members: immutable.SortedSet[Member]) =
    if (members.isEmpty) empty else empty.copy(members = members)

  private val leaderMemberStatus = Set[MemberStatus](Up, Leaving)
  private val convergenceMemberStatus = Set[MemberStatus](Up, Leaving)
  val convergenceSkipUnreachableWithMemberStatus = Set[MemberStatus](Down, Exiting)
  val removeUnreachableWithMemberStatus = Set[MemberStatus](Down, Exiting)

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
@SerialVersionUID(1L)
private[cluster] final case class Gossip(
  members:  immutable.SortedSet[Member], // sorted set of members with their status, sorted by address
  overview: GossipOverview              = GossipOverview(),
  version:  VectorClock                 = VectorClock()) { // vector clock version

  if (Cluster.isAssertInvariantsEnabled) assertInvariants()

  private def assertInvariants(): Unit = {

    if (members.exists(_.status == Removed))
      throw new IllegalArgumentException(s"Live members must have status [${Removed}], " +
        s"got [${members.filter(_.status == Removed)}]")

    val inReachabilityButNotMember = overview.reachability.allObservers diff members.map(_.uniqueAddress)
    if (inReachabilityButNotMember.nonEmpty)
      throw new IllegalArgumentException("Nodes not part of cluster in reachability table, got [%s]"
        format inReachabilityButNotMember.mkString(", "))

    val seenButNotMember = overview.seen diff members.map(_.uniqueAddress)
    if (seenButNotMember.nonEmpty)
      throw new IllegalArgumentException("Nodes not part of cluster have marked the Gossip as seen, got [%s]"
        format seenButNotMember.mkString(", "))
  }

  @transient private lazy val membersMap: Map[UniqueAddress, Member] =
    members.map(m ⇒ m.uniqueAddress → m)(collection.breakOut)

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
   */
  def seen(node: UniqueAddress): Gossip = {
    if (seenByNode(node)) this
    else this copy (overview = overview copy (seen = overview.seen + node))
  }

  /**
   * Marks the gossip as seen by only this node (address) by replacing the 'gossip.overview.seen'
   */
  def onlySeen(node: UniqueAddress): Gossip = {
    this copy (overview = overview copy (seen = Set(node)))
  }

  /**
   * The nodes that have seen the current version of the Gossip.
   */
  def seenBy: Set[UniqueAddress] = overview.seen

  /**
   * Has this Gossip been seen by this node.
   */
  def seenByNode(node: UniqueAddress): Boolean = overview.seen(node)

  /**
   * Merges the seen table of two Gossip instances.
   */
  def mergeSeen(that: Gossip): Gossip =
    this copy (overview = overview copy (seen = overview.seen union that.overview.seen))

  /**
   * Merges two Gossip instances including membership tables, and the VectorClock histories.
   */
  def merge(that: Gossip): Gossip = {

    // 1. merge vector clocks
    val mergedVClock = this.version merge that.version

    // 2. merge members by selecting the single Member with highest MemberStatus out of the Member groups
    val mergedMembers = Gossip.emptyMembers union Member.pickHighestPriority(this.members, that.members)

    // 3. merge reachability table by picking records with highest version
    val mergedReachability = this.overview.reachability.merge(
      mergedMembers.map(_.uniqueAddress),
      that.overview.reachability)

    // 4. Nobody can have seen this new gossip yet
    val mergedSeen = Set.empty[UniqueAddress]

    Gossip(mergedMembers, GossipOverview(mergedSeen, mergedReachability), mergedVClock)
  }

  /**
   * Checks if we have a cluster convergence. If there are any unreachable nodes then we can't have a convergence -
   * waiting for user to act (issuing DOWN) or leader to act (issuing DOWN through auto-down).
   *
   * @return true if convergence have been reached and false if not
   */
  def convergence(selfUniqueAddress: UniqueAddress): Boolean = {
    // First check that:
    //   1. we don't have any members that are unreachable, excluding observations from members
    //      that have status DOWN, or
    //   2. all unreachable members in the set have status DOWN or EXITING
    // Else we can't continue to check for convergence
    // When that is done we check that all members with a convergence
    // status is in the seen table, i.e. has seen this version
    val unreachable = reachabilityExcludingDownedObservers.allUnreachableOrTerminated.collect {
      case node if (node != selfUniqueAddress) ⇒ member(node)
    }
    unreachable.forall(m ⇒ Gossip.convergenceSkipUnreachableWithMemberStatus(m.status)) &&
      !members.exists(m ⇒ Gossip.convergenceMemberStatus(m.status) && !seenByNode(m.uniqueAddress))
  }

  lazy val reachabilityExcludingDownedObservers: Reachability = {
    val downed = members.collect { case m if m.status == Down ⇒ m }
    overview.reachability.removeObservers(downed.map(_.uniqueAddress))
  }

  def isLeader(node: UniqueAddress, selfUniqueAddress: UniqueAddress): Boolean =
    leader(selfUniqueAddress) == Some(node)

  def leader(selfUniqueAddress: UniqueAddress): Option[UniqueAddress] =
    leaderOf(members, selfUniqueAddress)

  def roleLeader(role: String, selfUniqueAddress: UniqueAddress): Option[UniqueAddress] =
    leaderOf(members.filter(_.hasRole(role)), selfUniqueAddress)

  private def leaderOf(mbrs: immutable.SortedSet[Member], selfUniqueAddress: UniqueAddress): Option[UniqueAddress] = {
    val reachableMembers =
      if (overview.reachability.isAllReachable) mbrs.filterNot(_.status == Down)
      else mbrs.filter(m ⇒ m.status != Down &&
        (overview.reachability.isReachable(m.uniqueAddress) || m.uniqueAddress == selfUniqueAddress))
    if (reachableMembers.isEmpty) None
    else reachableMembers.find(m ⇒ Gossip.leaderMemberStatus(m.status)).
      orElse(Some(reachableMembers.min(Member.leaderStatusOrdering))).map(_.uniqueAddress)
  }

  def allRoles: Set[String] = members.flatMap(_.roles)

  def isSingletonCluster: Boolean = members.size == 1

  def member(node: UniqueAddress): Member = {
    membersMap.getOrElse(
      node,
      Member.removed(node)) // placeholder for removed member
  }

  def hasMember(node: UniqueAddress): Boolean = membersMap.contains(node)

  def youngestMember: Member = {
    require(members.nonEmpty, "No youngest when no members")
    members.maxBy(m ⇒ if (m.upNumber == Int.MaxValue) 0 else m.upNumber)
  }

  def prune(removedNode: VectorClock.Node): Gossip = {
    val newVersion = version.prune(removedNode)
    if (newVersion eq version) this
    else copy(version = newVersion)
  }

  override def toString =
    s"Gossip(members = [${members.mkString(", ")}], overview = ${overview}, version = ${version})"
}

/**
 * INTERNAL API
 * Represents the overview of the cluster, holds the cluster convergence table and set with unreachable nodes.
 */
@SerialVersionUID(1L)
private[cluster] final case class GossipOverview(
  seen:         Set[UniqueAddress] = Set.empty,
  reachability: Reachability       = Reachability.empty) {

  override def toString =
    s"GossipOverview(reachability = [$reachability], seen = [${seen.mkString(", ")}])"
}

object GossipEnvelope {
  def apply(from: UniqueAddress, to: UniqueAddress, gossip: Gossip): GossipEnvelope =
    new GossipEnvelope(from, to, gossip, null, null)

  def apply(from: UniqueAddress, to: UniqueAddress, serDeadline: Deadline, ser: () ⇒ Gossip): GossipEnvelope =
    new GossipEnvelope(from, to, null, serDeadline, ser)
}

/**
 * INTERNAL API
 * Envelope adding a sender and receiver address to the gossip.
 * The reason for including the receiver address is to be able to
 * ignore messages that were intended for a previous incarnation of
 * the node with same host:port. The `uid` in the `UniqueAddress` is
 * different in that case.
 */
@SerialVersionUID(2L)
private[cluster] class GossipEnvelope private (
  val from:                    UniqueAddress,
  val to:                      UniqueAddress,
  @volatile var g:             Gossip,
  serDeadline:                 Deadline,
  @transient @volatile var ser:() ⇒ Gossip) extends ClusterMessage {

  def gossip: Gossip = {
    deserialize()
    g
  }

  private def deserialize(): Unit = {
    if ((g eq null) && (ser ne null)) {
      if (serDeadline.hasTimeLeft)
        g = ser()
      else
        g = Gossip.empty
      ser = null
    }
  }

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = {
    deserialize()
    this
  }

}

/**
 * INTERNAL API
 * When there are no known changes to the node ring a `GossipStatus`
 * initiates a gossip chat between two members. If the receiver has a newer
 * version it replies with a `GossipEnvelope`. If receiver has older version
 * it replies with its `GossipStatus`. Same versions ends the chat immediately.
 */
@SerialVersionUID(1L)
private[cluster] final case class GossipStatus(from: UniqueAddress, version: VectorClock) extends ClusterMessage
