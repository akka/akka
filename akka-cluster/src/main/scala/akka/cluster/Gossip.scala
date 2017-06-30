/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import scala.collection.{ SortedSet, immutable }
import ClusterSettings.Team
import MemberStatus._
import akka.annotation.InternalApi

import scala.concurrent.duration.Deadline

/**
 * INTERNAL API
 */
private[cluster] object Gossip {
  type Timestamp = Long
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
@InternalApi
private[cluster] final case class Gossip(
  members:    immutable.SortedSet[Member], // sorted set of members with their status, sorted by address
  overview:   GossipOverview                       = GossipOverview(),
  version:    VectorClock                          = VectorClock(), // vector clock version
  tombstones: Map[UniqueAddress, Gossip.Timestamp] = Map.empty) {

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
   * Remove all seen entries
   */
  def clearSeen(): Gossip = {
    this copy (overview = overview copy (seen = Set.empty))
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
   * Merges two Gossip instances including membership tables, tombstones, and the VectorClock histories.
   */
  def merge(that: Gossip): Gossip = {

    // 1. merge vector clocks
    val mergedVClock = this.version merge that.version

    // 2. merge sets of tombstones
    val mergedTombstones = tombstones ++ that.tombstones

    // 2. merge members by selecting the single Member with highest MemberStatus out of the Member groups
    val mergedMembers = Gossip.emptyMembers union Member.pickHighestPriority(this.members, that.members, mergedTombstones)

    // 3. merge reachability table by picking records with highest version
    val mergedReachability = this.overview.reachability.merge(
      mergedMembers.map(_.uniqueAddress),
      that.overview.reachability)

    // 4. Nobody can have seen this new gossip yet
    val mergedSeen = Set.empty[UniqueAddress]

    Gossip(mergedMembers, GossipOverview(mergedSeen, mergedReachability), mergedVClock, mergedTombstones)
  }

  /**
   * Checks if we have a cluster convergence. If there are any in team node pairs that cannot reach each other
   * then we can't have a convergence until those nodes reach each other again or one of them is downed
   *
   * @return true if convergence have been reached and false if not
   */
  def convergence(team: Team, selfUniqueAddress: UniqueAddress, exitingConfirmed: Set[UniqueAddress]): Boolean = {
    // Find cluster members in the team that are unreachable from other members of the team
    // excluding observations from members outside of the team, that have status DOWN or is passed in as confirmed exiting.
    val unreachableInTeam = teamReachabilityExcludingDownedObservers(team).allUnreachableOrTerminated.collect {
      case node if node != selfUniqueAddress && !exitingConfirmed(node) ⇒ member(node)
    }

    // If another member in the team that is UP or LEAVING and has not seen this gossip or is exiting
    // convergence cannot be reached
    def teamMemberHinderingConvergenceExists =
      members.exists(member ⇒
        member.team == team &&
          Gossip.convergenceMemberStatus(member.status) &&
          !(seenByNode(member.uniqueAddress) || exitingConfirmed(member.uniqueAddress))
      )

    // unreachables outside of the team or with status DOWN or EXITING does not affect convergence
    def allUnreachablesCanBeIgnored =
      unreachableInTeam.forall(unreachable ⇒ Gossip.convergenceSkipUnreachableWithMemberStatus(unreachable.status))

    allUnreachablesCanBeIgnored && !teamMemberHinderingConvergenceExists
  }

  lazy val reachabilityExcludingDownedObservers: Reachability = {
    val downed = members.collect { case m if m.status == Down ⇒ m }
    overview.reachability.removeObservers(downed.map(_.uniqueAddress))
  }

  /**
   * @return reachability for team nodes, with observations from outside the team or from downed nodes filtered out
   */
  def teamReachabilityExcludingDownedObservers(team: Team): Reachability = {
    val membersToExclude = members.collect { case m if m.status == Down || m.team != team ⇒ m.uniqueAddress }
    overview.reachability.removeObservers(membersToExclude).remove(members.collect { case m if m.team != team ⇒ m.uniqueAddress })
  }

  def teamMembers(team: Team): SortedSet[Member] =
    members.filter(_.team == team)

  def teamReachability(team: Team): Reachability =
    overview.reachability.removeObservers(members.collect { case m if m.team != team ⇒ m.uniqueAddress })

  def isTeamLeader(team: Team, node: UniqueAddress, selfUniqueAddress: UniqueAddress): Boolean =
    teamLeader(team, selfUniqueAddress).contains(node)

  def teamLeader(team: Team, selfUniqueAddress: UniqueAddress): Option[UniqueAddress] =
    leaderOf(team, members, selfUniqueAddress)

  def roleLeader(team: Team, role: String, selfUniqueAddress: UniqueAddress): Option[UniqueAddress] =
    leaderOf(team, members.filter(_.hasRole(role)), selfUniqueAddress)

  def leaderOf(team: Team, mbrs: immutable.SortedSet[Member], selfUniqueAddress: UniqueAddress): Option[UniqueAddress] = {
    val reachability = teamReachability(team)

    val reachableTeamMembers =
      if (reachability.isAllReachable) mbrs.filter(m ⇒ m.team == team && m.status != Down)
      else mbrs.filter(m ⇒
        m.team == team &&
          m.status != Down &&
          (reachability.isReachable(m.uniqueAddress) || m.uniqueAddress == selfUniqueAddress))
    if (reachableTeamMembers.isEmpty) None
    else reachableTeamMembers.find(m ⇒ Gossip.leaderMemberStatus(m.status))
      .orElse(Some(reachableTeamMembers.min(Member.leaderStatusOrdering)))
      .map(_.uniqueAddress)
  }

  def allTeams: Set[Team] = members.map(_.team)

  def allRoles: Set[String] = members.flatMap(_.roles)

  def isSingletonCluster: Boolean = members.size == 1

  /**
   * @return true if fromAddress should be able to reach toAddress based on the unreachability data and their
   *         respective teams
   */
  def isReachable(fromAddress: UniqueAddress, toAddress: UniqueAddress): Boolean =
    if (!hasMember(toAddress)) false
    else {
      val from = member(fromAddress)
      val to = member(toAddress)

      // if member is in the same team, we ignore cross-team unreachability
      if (from.team == to.team) teamReachabilityExcludingDownedObservers(from.team).isReachable(toAddress)
      else reachabilityExcludingDownedObservers.isReachable(toAddress)
    }

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

  def removeAll(nodes: Iterable[UniqueAddress], removalTimestamp: Long): Gossip = {
    nodes.foldLeft(this)((gossip, node) ⇒ gossip.remove(node, removalTimestamp))
  }

  def update(updatedMembers: immutable.SortedSet[Member]): Gossip = {
    copy(members = updatedMembers union members)
  }

  /**
   * Remove the given member from the set of members and mark it's removal with a tombstone to avoid having it
   * reintroduced when merging with another gossip that has not seen the removal.
   */
  def remove(node: UniqueAddress, removalTimestamp: Long): Gossip = {
    // removing REMOVED nodes from the `seen` table
    val newSeen = overview.seen - node
    // removing REMOVED nodes from the `reachability` table
    val newReachability = overview.reachability.remove(node :: Nil)
    val newOverview = overview.copy(seen = newSeen, reachability = newReachability)

    // Clear the VectorClock when member is removed. The change made by the leader is stamped
    // and will propagate as is if there are no other changes on other nodes.
    // If other concurrent changes on other nodes (e.g. join) the pruning is also
    // taken care of when receiving gossips.
    val newVersion = version.prune(VectorClock.Node(ClusterCoreDaemon.vclockName(node)))
    val newMembers = members.filterNot(_.uniqueAddress == node)
    val newTombstones = tombstones + (node → removalTimestamp)
    copy(version = newVersion, members = newMembers, overview = newOverview, tombstones = newTombstones)
  }

  def markAsDown(member: Member): Gossip = {
    // replace member (changed status)
    val newMembers = members - member + member.copy(status = Down)
    // remove nodes marked as DOWN from the `seen` table
    val newSeen = overview.seen - member.uniqueAddress

    // update gossip overview
    val newOverview = overview copy (seen = newSeen)
    copy(members = newMembers, overview = newOverview) // update gossip
  }

  def prune(removedNode: VectorClock.Node): Gossip = {
    val newVersion = version.prune(removedNode)
    if (newVersion eq version) this
    else copy(version = newVersion)
  }

  def pruneTombstones(removeEarlierThan: Gossip.Timestamp): Gossip = {
    val newTombstones = tombstones.filter { case (_, timestamp) ⇒ timestamp < removeEarlierThan }
    if (newTombstones.size == tombstones.size) this
    else copy(tombstones = newTombstones)
  }

  override def toString =
    s"Gossip(members = [${members.mkString(", ")}], overview = $overview, version = $version, tombstones = $tombstones)"
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
