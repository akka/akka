/**
 * Copyright (C) 2015-2020 Lightbend Inc.  <https://www.lightbend.com>
 */
package akka.cluster.sbr

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster._
import akka.pattern.pipe

import scala.collection.immutable
import scala.concurrent.duration.{ FiniteDuration, _ }
import akka.coordination.lease.scaladsl.Lease

/**
 * INTERNAL API
 */
private[akka] object SplitBrainResolver {

  def props(stableAfter: FiniteDuration, strategy: Strategy): Props =
    Props(new SplitBrainResolver(stableAfter, strategy))

  case object Tick

  /**
   * Response (result) of the acquire lease request.
   */
  final case class AcquireLeaseResult(holdingLease: Boolean)

  /**
   * Response (result) of the release lease request.
   */
  final case class ReleaseLeaseResult(released: Boolean)

  /**
   * For delayed acquire of the lease.
   */
  case object AcquireLease

  sealed trait ReleaseLeaseCondition
  object ReleaseLeaseCondition {
    case object NoLease extends ReleaseLeaseCondition
    final case class WhenMembersRemoved(nodes: Set[UniqueAddress]) extends ReleaseLeaseCondition
    final case class WhenTimeElapsed(deadline: Deadline) extends ReleaseLeaseCondition
  }

  sealed trait Decision {
    def isIndirectlyConnected: Boolean
  }
  case object DownReachable extends Decision {
    override def isIndirectlyConnected = false
  }
  case object DownUnreachable extends Decision {
    override def isIndirectlyConnected = false
  }
  case object DownAll extends Decision {
    override def isIndirectlyConnected = false
  }
  case object DownIndirectlyConnected extends Decision {
    override def isIndirectlyConnected = true
  }
  sealed trait AcquireLeaseDecision extends Decision {
    def acquireDelay: FiniteDuration
  }
  final case class AcquireLeaseAndDownUnreachable(acquireDelay: FiniteDuration) extends AcquireLeaseDecision {
    override def isIndirectlyConnected = false
  }
  final case class AcquireLeaseAndDownIndirectlyConnected(acquireDelay: FiniteDuration) extends AcquireLeaseDecision {
    override def isIndirectlyConnected = true
  }
  case object ReverseDownIndirectlyConnected extends Decision {
    override def isIndirectlyConnected = true
  }

  final case class ReachabilityChangedStats(
      firstChangeTimestamp: Long,
      latestChangeTimestamp: Long,
      changeCount: Long) {

    def isEmpty: Boolean =
      changeCount == 0

    override def toString: String = {
      if (isEmpty)
        "reachability unchanged"
      else {
        val now = System.nanoTime()
        s"reachability changed $changeCount times since ${(now - firstChangeTimestamp).nanos.toMillis} ms ago, " +
        s"latest change was ${(now - latestChangeTimestamp).nanos.toMillis} ms ago"
      }
    }
  }

  abstract class Strategy(val selfDc: DataCenter) extends NoSerializationVerificationNeeded {

    // may contain Joining and WeaklyUp
    private var _unreachable: Set[UniqueAddress] = Set.empty[UniqueAddress]

    def unreachable: Set[UniqueAddress] = _unreachable

    def unreachable(m: Member): Boolean = _unreachable(m.uniqueAddress)

    private var _reachability: Reachability = Reachability.empty

    private var _seenBy: Set[Address] = Set.empty

    protected def ordering: Ordering[Member] = Member.ordering

    // all members in self DC, both joining and up.
    private var _allMembers: immutable.SortedSet[Member] = immutable.SortedSet.empty(ordering)

    def role: Option[String]

    // all Joining and WeaklyUp members in self DC
    def joining: immutable.SortedSet[Member] =
      _allMembers.filter(m => m.status == MemberStatus.Joining || m.status == MemberStatus.WeaklyUp)

    // all members in self DC, both joining and up.
    def allMembersInDC: immutable.SortedSet[Member] = _allMembers

    /**
     * All members in self DC, but doesn't contain Joining, WeaklyUp, Down and Exiting.
     */
    def members: immutable.SortedSet[Member] =
      members(includingPossiblyUp = false, excludingPossiblyExiting = false)

    /**
     * All members in self DC, but doesn't contain Joining, WeaklyUp, Down and Exiting.
     *
     * When `includingPossiblyUp=true` it also includes Joining and WeaklyUp members that could have been
     * changed to Up on the other side of a partition.
     *
     * When `excludingPossiblyExiting=true` it doesn't include Leaving members that could have been
     * changed to Exiting on the other side of the partition.
     */
    def members(includingPossiblyUp: Boolean, excludingPossiblyExiting: Boolean): immutable.SortedSet[Member] =
      _allMembers.filterNot(
        m =>
          (!includingPossiblyUp && m.status == MemberStatus.Joining) ||
          (!includingPossiblyUp && m.status == MemberStatus.WeaklyUp) ||
          (excludingPossiblyExiting && m.status == MemberStatus.Leaving) ||
          m.status == MemberStatus.Down ||
          m.status == MemberStatus.Exiting)

    def membersWithRole: immutable.SortedSet[Member] =
      membersWithRole(includingPossiblyUp = false, excludingPossiblyExiting = false)

    def membersWithRole(includingPossiblyUp: Boolean, excludingPossiblyExiting: Boolean): immutable.SortedSet[Member] =
      role match {
        case None    => members(includingPossiblyUp, excludingPossiblyExiting)
        case Some(r) => members(includingPossiblyUp, excludingPossiblyExiting).filter(_.hasRole(r))
      }

    def reachableMembers: immutable.SortedSet[Member] =
      reachableMembers(includingPossiblyUp = false, excludingPossiblyExiting = false)

    def reachableMembers(
        includingPossiblyUp: Boolean,
        excludingPossiblyExiting: Boolean): immutable.SortedSet[Member] = {
      val mbrs = members(includingPossiblyUp, excludingPossiblyExiting)
      if (unreachable.isEmpty) mbrs
      else mbrs.filter(m => !unreachable(m))
    }

    def reachableMembersWithRole: immutable.SortedSet[Member] =
      reachableMembersWithRole(includingPossiblyUp = false, excludingPossiblyExiting = false)

    def reachableMembersWithRole(
        includingPossiblyUp: Boolean,
        excludingPossiblyExiting: Boolean): immutable.SortedSet[Member] =
      role match {
        case None    => reachableMembers(includingPossiblyUp, excludingPossiblyExiting)
        case Some(r) => reachableMembers(includingPossiblyUp, excludingPossiblyExiting).filter(_.hasRole(r))
      }

    def unreachableMembers: immutable.SortedSet[Member] =
      unreachableMembers(includingPossiblyUp = false, excludingPossiblyExiting = false)

    def unreachableMembers(
        includingPossiblyUp: Boolean,
        excludingPossiblyExiting: Boolean): immutable.SortedSet[Member] = {
      if (unreachable.isEmpty) immutable.SortedSet.empty
      else members(includingPossiblyUp, excludingPossiblyExiting).filter(unreachable)
    }

    def unreachableMembersWithRole: immutable.SortedSet[Member] =
      unreachableMembersWithRole(includingPossiblyUp = false, excludingPossiblyExiting = false)

    def unreachableMembersWithRole(
        includingPossiblyUp: Boolean,
        excludingPossiblyExiting: Boolean): immutable.SortedSet[Member] =
      role match {
        case None    => unreachableMembers(includingPossiblyUp, excludingPossiblyExiting)
        case Some(r) => unreachableMembers(includingPossiblyUp, excludingPossiblyExiting).filter(_.hasRole(r))
      }

    def addUnreachable(m: Member): Unit = {
      require(m.dataCenter == selfDc)

      add(m)
      _unreachable = _unreachable + m.uniqueAddress
    }

    def addReachable(m: Member): Unit = {
      require(m.dataCenter == selfDc)

      add(m)
      _unreachable = _unreachable - m.uniqueAddress
    }

    def add(m: Member): Unit = {
      require(m.dataCenter == selfDc)

      removeFromAllMembers(m)
      _allMembers += m
    }

    def remove(m: Member): Unit = {
      require(m.dataCenter == selfDc)

      removeFromAllMembers(m)
      _unreachable -= m.uniqueAddress
    }

    private def removeFromAllMembers(m: Member): Unit = {
      if (ordering eq Member.ordering) {
        _allMembers -= m
      } else {
        // must use filterNot for removals/replace in the SortedSet when
        // ageOrdering is using upNumber and that will change when Joining -> Up
        _allMembers = _allMembers.filterNot(_.uniqueAddress == m.uniqueAddress)
      }
    }

    def reachability: Reachability =
      _reachability

    private def isInSelfDc(node: UniqueAddress): Boolean = {
      _allMembers.exists(m => m.uniqueAddress == node && m.dataCenter == selfDc)
    }

    /**
     * @return true if it was changed
     */
    private[sbr] def setReachability(r: Reachability): Boolean = {
      // skip records with Reachability.Reachable, and skip records related to other DC
      val newReachability = r.filterRecords(
        record =>
          (record.status == Reachability.Unreachable || record.status == Reachability.Terminated) &&
          isInSelfDc(record.observer) && isInSelfDc(record.subject))
      val oldReachability = _reachability

      val changed =
        if (oldReachability.records.size != newReachability.records.size)
          true
        else
          oldReachability.records.map(r => r.observer -> r.subject).toSet !=
            newReachability.records.map(r => r.observer -> r.subject).toSet

      _reachability = newReachability
      changed
    }

    def seenBy: Set[Address] =
      _seenBy

    def setSeenBy(s: Set[Address]): Unit =
      _seenBy = s

    /**
     * Nodes that are marked as unreachable but can communicate with gossip via a 3rd party.
     *
     * Cycle in unreachability graph corresponds to that some node is both
     * observing another node as unreachable, and is also observed as unreachable by someone
     * else.
     *
     * Another indication of indirectly connected nodes is if a node is marked as unreachable,
     * but it has still marked current gossip state as seen.
     *
     * Those cases will not happen for clean splits and crashed nodes.
     */
    def indirectlyConnected: Set[UniqueAddress] = {
      indirectlyConnectedFromIntersectionOfObserversAndSubjects.union(indirectlyConnectedFromSeenCurrentGossip)
    }

    private def indirectlyConnectedFromIntersectionOfObserversAndSubjects: Set[UniqueAddress] = {
      // cycle in unreachability graph
      // TODO can't use r.allObservers, because reachability.filterRecords doesn't update versions (issue #25950 in Akka)
      val observers = reachability.records.map(_.observer).toSet
      observers.intersect(reachability.allUnreachableOrTerminated)
    }

    private def indirectlyConnectedFromSeenCurrentGossip: Set[UniqueAddress] = {
      reachability.records.flatMap { r =>
        if (seenBy(r.subject.address)) r.observer :: r.subject :: Nil
        else Nil
      }.toSet
    }

    def hasIndirectlyConnected: Boolean = indirectlyConnected.nonEmpty

    def unreachableButNotIndirectlyConnected: Set[UniqueAddress] = unreachable.diff(indirectlyConnected)

    def nodesToDown(decision: Decision = decide()): Set[UniqueAddress] = {
      val downable = members
        .union(joining)
        .filterNot(m => m.status == MemberStatus.Down || m.status == MemberStatus.Exiting)
        .map(_.uniqueAddress)
      decision match {
        case DownUnreachable | AcquireLeaseAndDownUnreachable(_)                 => downable.intersect(unreachable)
        case DownReachable                                                       => downable.diff(unreachable)
        case DownAll                                                             => downable
        case DownIndirectlyConnected | AcquireLeaseAndDownIndirectlyConnected(_) =>
          // Down nodes that have been marked as unreachable via some network links but they are still indirectly
          // connected via other links. It will keep other "normal" nodes.
          // If there is a combination of indirectly connected nodes and a clean network partition (or node crashes)
          // it will combine the above decision with the ordinary decision, e.g. keep majority, after excluding
          // failure detection observations between the indirectly connected nodes.
          // Also include nodes that corresponds to the decision without the unreachability observations from
          // the indirectly connected nodes
          downable.intersect(indirectlyConnected.union(additionalNodesToDownWhenIndirectlyConnected))
        case ReverseDownIndirectlyConnected =>
          // indirectly connected + all reachable
          downable.intersect(indirectlyConnected).union(downable.diff(unreachable))
      }
    }

    private def additionalNodesToDownWhenIndirectlyConnected: Set[UniqueAddress] = {
      if (unreachableButNotIndirectlyConnected.isEmpty)
        Set.empty
      else {
        val originalUnreachable = _unreachable
        val originalReachability = _reachability
        try {
          val intersectionOfObserversAndSubjects = indirectlyConnectedFromIntersectionOfObserversAndSubjects
          val haveSeenCurrentGossip = indirectlyConnectedFromSeenCurrentGossip
          // remove records between the indirectly connected
          _reachability = reachability.filterRecords(
            r =>
              !((intersectionOfObserversAndSubjects(r.observer) && intersectionOfObserversAndSubjects(r.subject)) ||
              (haveSeenCurrentGossip(r.observer) && haveSeenCurrentGossip(r.subject))))
          _unreachable = reachability.allUnreachableOrTerminated
          val additionalDecision = decide()

          if (additionalDecision.isIndirectlyConnected)
            throw new IllegalStateException(
              s"SBR double $additionalDecision decision, downing all instead. " +
              s"originalReachability: [$originalReachability], filtered reachability [$reachability], " +
              s"still indirectlyConnected: [$indirectlyConnected], seenBy: [$seenBy]")

          nodesToDown(additionalDecision)
        } finally {
          _unreachable = originalUnreachable
          _reachability = originalReachability
        }
      }
    }

    def isAllUnreachableDownOrExiting: Boolean = {
      _unreachable.isEmpty ||
      unreachableMembers.forall(m => m.status == MemberStatus.Down || m.status == MemberStatus.Exiting)
    }

    def reverseDecision(decision: Decision): Decision = {
      decision match {
        case DownUnreachable                           => DownReachable
        case AcquireLeaseAndDownUnreachable(_)         => DownReachable
        case DownReachable                             => DownUnreachable
        case DownAll                                   => DownAll
        case DownIndirectlyConnected                   => ReverseDownIndirectlyConnected
        case AcquireLeaseAndDownIndirectlyConnected(_) => ReverseDownIndirectlyConnected
        case ReverseDownIndirectlyConnected            => DownIndirectlyConnected
      }
    }

    def decide(): Decision

    def lease: Option[Lease] = None

  }

  /**
   * Down the unreachable nodes if the number of remaining nodes are greater than or equal to the
   * given `quorumSize`. Otherwise down the reachable nodes, i.e. it will shut down that side of the partition.
   * In other words, the `quorumSize` defines the minimum number of nodes that the cluster must have to be operational.
   * If there are unreachable nodes when starting up the cluster, before reaching this limit,
   * the cluster may shutdown itself immediately. This is not an issue if you start all nodes at
   * approximately the same time.
   *
   * Note that you must not add more members to the cluster than `quorumSize * 2 - 1`, because then
   * both sides may down each other and thereby form two separate clusters. For example,
   * quorum quorumSize configured to 3 in a 6 node cluster may result in a split where each side
   * consists of 3 nodes each, i.e. each side thinks it has enough nodes to continue by
   * itself. A warning is logged if this recommendation is violated.
   *
   * If the `role` is defined the decision is based only on members with that `role`.
   *
   * It is only counting members within the own data center.
   */
  class StaticQuorum(selfDc: DataCenter, val quorumSize: Int, override val role: Option[String])
      extends Strategy(selfDc) {

    override def decide(): Decision = {
      if (isTooManyMembers)
        DownAll
      else if (hasIndirectlyConnected)
        DownIndirectlyConnected
      else if (membersWithRole.size - unreachableMembersWithRole.size >= quorumSize)
        DownUnreachable
      else
        DownReachable
    }

    def isTooManyMembers: Boolean =
      membersWithRole.size > (quorumSize * 2 - 1)
  }

  /**
   * Down the unreachable nodes if the current node is in the majority part based the last known
   * membership information. Otherwise down the reachable nodes, i.e. the own part. If the the
   * parts are of equal size the part containing the node with the lowest address is kept.
   *
   * If the `role` is defined the decision is based only on members with that `role`.
   *
   * Note that if there are more than two partitions and none is in majority each part
   * will shutdown itself, terminating the whole cluster.
   *
   * It is only counting members within the own data center.
   */
  class KeepMajority(selfDc: DataCenter, override val role: Option[String]) extends Strategy(selfDc) {

    override def decide(): Decision = {
      if (hasIndirectlyConnected)
        DownIndirectlyConnected
      else {
        val ms = membersWithRole
        if (ms.isEmpty)
          DownAll // no node with matching role
        else {
          val reachableSize = reachableMembersWithRole.size
          val unreachableSize = unreachableMembersWithRole.size

          majorityDecision(reachableSize, unreachableSize, ms.head) match {
            case DownUnreachable =>
              majorityDecisionWhenIncludingMembershipChangesEdgeCase() match {
                case DownUnreachable => DownUnreachable // same conclusion
                case _               => DownAll // different conclusion, safest to DownAll
              }
            case decision => decision
          }

        }
      }
    }

    private def majorityDecision(thisSide: Int, otherSide: Int, lowest: Member): Decision = {
      if (thisSide == otherSide) {
        // equal size, keep the side with the lowest address (first in members)
        if (unreachable(lowest)) DownReachable else DownUnreachable
      } else if (thisSide > otherSide) {
        // we are in majority
        DownUnreachable
      } else {
        // we are in minority
        DownReachable
      }
    }

    /**
     * Check for edge case when membership change happens at the same time as partition.
     * Count Joining and WeaklyUp on other side since those might be Up on other side.
     * Don't count Leaving on this side since those might be Exiting on other side.
     * Note that the membership changes we are looking for will only be done when all
     * members have seen previous state, i.e. when a member is moved to Up everybody
     * has seen it joining.
     */
    private def majorityDecisionWhenIncludingMembershipChangesEdgeCase(): Decision = {
      // for this side we count as few as could be possible (excluding joining, excluding leaving)
      val ms = membersWithRole(includingPossiblyUp = false, excludingPossiblyExiting = true)
      if (ms.isEmpty) {
        DownAll
      } else {
        val thisSideReachableSize =
          reachableMembersWithRole(includingPossiblyUp = false, excludingPossiblyExiting = true).size
        // for other side we count as many as could be possible (including joining, including leaving)
        val otherSideUnreachableSize =
          unreachableMembersWithRole(includingPossiblyUp = true, excludingPossiblyExiting = false).size
        majorityDecision(thisSideReachableSize, otherSideUnreachableSize, ms.head)
      }
    }

  }

  /**
   * Down the part that does not contain the oldest member (current singleton).
   *
   * There is one exception to this rule if `downIfAlone` is defined to `true`.
   * Then, if the oldest node has partitioned from all other nodes the oldest will
   * down itself and keep all other nodes running. The strategy will not down the
   * single oldest node when it is the only remaining node in the cluster.
   *
   * Note that if the oldest node crashes the others will remove it from the cluster
   * when `downIfAlone` is `true`, otherwise they will down themselves if the
   * oldest node crashes, i.e. shutdown the whole cluster together with the oldest node.
   *
   * If the `role` is defined the decision is based only on members with that `role`,
   * i.e. using the oldest member (singleton) within the nodes with that role.
   *
   * It is only using members within the own data center, i.e. oldest within the
   * data center.
   */
  class KeepOldest(selfDc: DataCenter, val downIfAlone: Boolean, override val role: Option[String])
      extends Strategy(selfDc) {

    // sort by age, oldest first
    override def ordering = Member.ageOrdering

    override def decide(): Decision = {
      if (hasIndirectlyConnected)
        DownIndirectlyConnected
      else {
        val ms = membersWithRole
        if (ms.isEmpty)
          DownAll // no node with matching role
        else {
          val oldest = ms.head
          val oldestIsReachable = !unreachable(oldest)
          val reachableCount = reachableMembersWithRole.size
          val unreachableCount = unreachableMembersWithRole.size

          oldestDecision(oldestIsReachable, reachableCount, unreachableCount) match {
            case DownUnreachable =>
              oldestDecisionWhenIncludingMembershipChangesEdgeCase() match {
                case DownUnreachable => DownUnreachable // same conclusion
                case _               => DownAll // different conclusion, safest to DownAll
              }
            case decision => decision
          }

        }
      }
    }

    private def oldestDecision(oldestIsOnThisSide: Boolean, thisSide: Int, otherSide: Int): Decision = {
      if (oldestIsOnThisSide) {
        // if there are only 2 nodes in the cluster it is better to keep the oldest, even though it is alone
        // E.g. 2 nodes: thisSide=1, otherSide=1 => DownUnreachable, i.e. keep the oldest
        //               even though it is alone (because the node on the other side is no better)
        // E.g. 3 nodes: thisSide=1, otherSide=2 => DownReachable, i.e. shut down the
        //               oldest because it is alone
        if (downIfAlone && thisSide == 1 && otherSide >= 2) DownReachable
        else DownUnreachable
      } else {
        if (downIfAlone && otherSide == 1 && thisSide >= 2) DownUnreachable
        else DownReachable
      }
    }

    /**
     * Check for edge case when membership change happens at the same time as partition.
     * Exclude Leaving on this side because those could be Exiting on other side.
     *
     * When `downIfAlone` also consider Joining and WeaklyUp since those might be Up on other side,
     * and thereby flip the alone test.
     */
    private def oldestDecisionWhenIncludingMembershipChangesEdgeCase(): Decision = {
      val ms = membersWithRole(includingPossiblyUp = false, excludingPossiblyExiting = true)
      if (ms.isEmpty) {
        DownAll
      } else {
        val oldest = ms.head
        val oldestIsReachable = !unreachable(oldest)
        // Joining and WeaklyUp are only relevant when downIfAlone = true
        val includingPossiblyUp = downIfAlone
        val reachableCount = reachableMembersWithRole(includingPossiblyUp, excludingPossiblyExiting = true).size
        val unreachableCount = unreachableMembersWithRole(includingPossiblyUp, excludingPossiblyExiting = true).size

        oldestDecision(oldestIsReachable, reachableCount, unreachableCount)
      }
    }
  }

  /**
   * Down all nodes unconditionally.
   */
  class DownAllNodes(selfDc: DataCenter) extends Strategy(selfDc) {

    override def decide(): Decision =
      DownAll

    override def role = None
  }

  /**
   * Keep the part that can acquire the lease, and down the other part.
   *
   * Best effort is to keep the side that has most nodes, i.e. the majority side.
   * This is achieved by adding a delay before trying to acquire the lease on the
   * minority side.
   *
   * If the `role` is defined the majority/minority is based only on members with that `role`.
   * It is only counting members within the own data center.
   */
  class LeaseMajority(
      selfDc: DataCenter,
      override val role: Option[String],
      _lease: Lease,
      acquireLeaseDelayForMinority: FiniteDuration)
      extends Strategy(selfDc) {

    override val lease: Option[Lease] = Some(_lease)

    override def decide(): Decision = {
      if (hasIndirectlyConnected)
        AcquireLeaseAndDownIndirectlyConnected(Duration.Zero)
      else
        AcquireLeaseAndDownUnreachable(acquireLeaseDelay)
    }

    private def acquireLeaseDelay: FiniteDuration =
      if (isInMinority) acquireLeaseDelayForMinority else Duration.Zero

    private def isInMinority: Boolean = {
      val ms = membersWithRole
      if (ms.isEmpty)
        false // no node with matching role
      else {
        val unreachableSize = unreachableMembersWithRole.size
        val membersSize = ms.size

        if (unreachableSize * 2 == membersSize) {
          // equal size, try to keep the side with the lowest address (first in members)
          unreachable(ms.head)
        } else if (unreachableSize * 2 < membersSize) {
          // we are in majority
          false
        } else {
          // we are in minority
          true
        }
      }
    }
  }

}

/**
 * INTERNAL API
 *
 * Unreachable members will be downed by this actor according to the given strategy.
 * It is active on the leader node in the cluster.
 *
 * The implementation is split into two classes SplitBrainResolver and SplitBrainResolverBase to be
 * able to unit test the logic without running cluster.
 */
private[akka] class SplitBrainResolver(stableAfter: FiniteDuration, strategy: SplitBrainResolver.Strategy)
    extends SplitBrainResolverBase(stableAfter, strategy) {

  val cluster = Cluster(context.system)

  log.info(
    "SBR started. Config: stableAfter: {} ms, strategy: {}, selfUniqueAddress: {}, selfDc: {}",
    stableAfter.toMillis,
    strategy.getClass.getSimpleName,
    selfUniqueAddress,
    selfDc)

  override def selfUniqueAddress = cluster.selfUniqueAddress
  override def selfDc = cluster.selfDataCenter

  // re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[ClusterDomainEvent])
    super.preStart()
  }
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    tickTask.cancel()
    super.postStop()
  }

  override def down(node: Address): Unit = {
    cluster.down(node)
  }

}

/**
 * INTERNAL API
 *
 * The implementation is split into two classes SplitBrainResolver and SplitBrainResolverBase to be
 * able to unit test the logic without running cluster.
 */
private[akka] abstract class SplitBrainResolverBase(stableAfter: FiniteDuration, strategy: SplitBrainResolver.Strategy)
    extends Actor
    with ActorLogging
    with Stash {

  import SplitBrainResolver._
  import SplitBrainResolver.ReleaseLeaseCondition.NoLease

  def selfUniqueAddress: UniqueAddress

  def selfDc: DataCenter

  def down(node: Address): Unit

  // would be better as constructor parameter, but don't want to break Cinnamon instrumentation
  private val settings = new SplitBrainResolverSettings(context.system.settings.config)

  def downAllWhenUnstable: FiniteDuration =
    settings.DownAllWhenUnstable

  private val releaseLeaseAfter = stableAfter * 2

  def tickInterval: FiniteDuration = 1.second

  import context.dispatcher
  val tickTask = {
    val interval = tickInterval
    context.system.scheduler.scheduleWithFixedDelay(interval, interval, self, Tick)
  }

  var leader = false
  var selfMemberAdded = false

  // overridden in tests
  protected def newStableDeadline(): Deadline = Deadline.now + stableAfter
  var stableDeadline: Deadline = _
  def resetStableDeadline(): Unit = {
    stableDeadline = newStableDeadline()
  }

  resetStableDeadline()

  private var reachabilityChangedStats: ReachabilityChangedStats =
    ReachabilityChangedStats(System.nanoTime(), System.nanoTime(), 0)

  private def resetReachabilityChangedStats(): Unit = {
    val now = System.nanoTime()
    reachabilityChangedStats = ReachabilityChangedStats(now, now, 0)
  }

  private def resetReachabilityChangedStatsIfAllUnreachableDowned(): Unit = {
    if (!reachabilityChangedStats.isEmpty && strategy.isAllUnreachableDownOrExiting) {
      log.debug("SBR resetting reachability stats, after all unreachable healed, downed or removed")
      resetReachabilityChangedStats()
    }
  }

  private var releaseLeaseCondition: ReleaseLeaseCondition = NoLease

  /** Helper to wrap updates to strategy info with, so that stable-after timer is reset and information is logged about state change */
  def mutateMemberInfo(resetStable: Boolean)(f: () => Unit): Unit = {
    val unreachableBefore = strategy.unreachable.size
    f()
    val unreachableAfter = strategy.unreachable.size

    def earliestTimeOfDecision: String =
      Instant.now().plus(stableAfter.toMillis, ChronoUnit.MILLIS).toString

    if (resetStable) {
      if (isResponsible) {
        if (unreachableBefore == 0 && unreachableAfter > 0) {
          log.info(
            "SBR found unreachable members, waiting for stable-after = {} ms before taking downing decision. " +
            "Now {} unreachable members found. Downing decision will not be made before {}.",
            stableAfter.toMillis,
            unreachableAfter,
            earliestTimeOfDecision)
        } else if (unreachableBefore > 0 && unreachableAfter == 0) {
          log.info(
            "SBR found all unreachable members healed during stable-after period, no downing decision necessary for now.")
        } else if (unreachableAfter > 0) {
          log.info(
            "SBR found unreachable members changed during stable-after period. Resetting timer. " +
            "Now {} unreachable members found. Downing decision will not be made before {}.",
            unreachableAfter,
            earliestTimeOfDecision)
        }
        // else no unreachable members found but set of members changed
      }

      log.debug("SBR reset stable deadline when members/unreachable changed")
      resetStableDeadline()
    }
  }

  /** Helper to wrap updates to `leader` and `selfMemberAdded` to log changes in responsibility status */
  def mutateResponsibilityInfo(f: () => Unit): Unit = {
    val responsibleBefore = isResponsible
    f()
    val responsibleAfter = isResponsible

    if (!responsibleBefore && responsibleAfter)
      log.info(
        "This node is now the leader responsible for taking SBR decisions among the reachable nodes " +
        "(more leaders may exist).")
    else if (responsibleBefore && !responsibleAfter)
      log.info("This node is not the leader any more and not responsible for taking SBR decisions.")

    if (leader && !selfMemberAdded)
      log.debug("This node is leader but !selfMemberAdded.")
  }

  private var unreachableDataCenters = Set.empty[DataCenter]

  override def postStop(): Unit = {
    tickTask.cancel()
    if (releaseLeaseCondition != NoLease) {
      log.info(
        "SBR is stopped and owns the lease. The lease will not be released until after the " +
        "lease heartbeat-timeout.")
    }
    super.postStop()
  }

  def receive: Receive = {
    case SeenChanged(_, seenBy)       => seenChanged(seenBy)
    case MemberJoined(m)              => addJoining(m)
    case MemberWeaklyUp(m)            => addWeaklyUp(m)
    case MemberUp(m)                  => addUp(m)
    case MemberLeft(m)                => leaving(m)
    case UnreachableMember(m)         => unreachableMember(m)
    case MemberDowned(m)              => unreachableMember(m)
    case MemberExited(m)              => unreachableMember(m)
    case ReachableMember(m)           => reachableMember(m)
    case ReachabilityChanged(r)       => reachabilityChanged(r)
    case MemberRemoved(m, _)          => remove(m)
    case UnreachableDataCenter(dc)    => unreachableDataCenter(dc)
    case ReachableDataCenter(dc)      => reachableDataCenter(dc)
    case LeaderChanged(leaderOption)  => leaderChanged(leaderOption)
    case ReleaseLeaseResult(released) => releaseLeaseResult(released)
    case Tick                         => tick()
    case _: ClusterDomainEvent        => // not interested in other events
  }

  private def leaderChanged(leaderOption: Option[Address]): Unit = {
    mutateResponsibilityInfo { () =>
      leader = leaderOption.contains(selfUniqueAddress.address)
    }
  }

  private def tick(): Unit = {
    // note the DownAll due to instability is running on all nodes to make that decision as quickly and
    // aggressively as possible if time is out
    if (reachabilityChangedStats.changeCount > 0) {
      val now = System.nanoTime()
      val durationSinceLatestChange = (now - reachabilityChangedStats.latestChangeTimestamp).nanos
      val durationSinceFirstChange = (now - reachabilityChangedStats.firstChangeTimestamp).nanos

      if (durationSinceLatestChange > (stableAfter * 2)) {
        log.debug("SBR no reachability changes within {} ms, resetting stats", (stableAfter * 2).toMillis)
        resetReachabilityChangedStats()
      } else if (downAllWhenUnstable > Duration.Zero &&
                 durationSinceFirstChange > (stableAfter + downAllWhenUnstable)) {
        log.warning("SBR detected instability and will down all nodes: {}", reachabilityChangedStats)
        actOnDecision(DownAll)
      }
    }

    if (isResponsible && strategy.unreachable.nonEmpty && stableDeadline.isOverdue()) {
      strategy.decide() match {
        case decision: AcquireLeaseDecision =>
          strategy.lease match {
            case Some(lease) =>
              if (lease.checkLease()) {
                log.info("SBR has acquired lease for decision [{}]", decision)
                actOnDecision(decision)
              } else {
                if (decision.acquireDelay == Duration.Zero)
                  acquireLease() // reply message is AcquireLeaseResult
                else {
                  log.debug("SBR delayed attempt to acquire lease for [{} ms]", decision.acquireDelay.toMillis)
                  context.system.scheduler.scheduleOnce(decision.acquireDelay, self, AcquireLease)
                }
                context.become(waitingForLease(decision))
              }
            case None =>
              throw new IllegalStateException("Unexpected lease decision although lease is not configured")
          }

        case decision =>
          actOnDecision(decision)
      }
    }

    releaseLeaseCondition match {
      case ReleaseLeaseCondition.WhenTimeElapsed(deadline) =>
        if (deadline.isOverdue())
          releaseLease() // reply message is ReleaseLeaseResult, which will update the releaseLeaseCondition
      case _ =>
      // no lease or first waiting for downed nodes to be removed
    }
  }

  private def acquireLease(): Unit = {
    log.debug("SBR trying to acquire lease")
    strategy.lease.foreach(
      _.acquire()
        .recover {
          case t =>
            log.error(t, "SBR acquire of lease failed")
            false
        }
        .map(AcquireLeaseResult)
        .pipeTo(self))
  }

  def waitingForLease(decision: Decision): Receive = {
    case AcquireLease =>
      acquireLease() // reply message is LeaseResult

    case AcquireLeaseResult(holdingLease) =>
      if (holdingLease) {
        log.info("SBR acquired lease for decision [{}]", decision)
        val downedNodes = actOnDecision(decision)
        releaseLeaseCondition = releaseLeaseCondition match {
          case ReleaseLeaseCondition.WhenMembersRemoved(nodes) =>
            ReleaseLeaseCondition.WhenMembersRemoved(nodes.union(downedNodes))
          case _ =>
            if (downedNodes.isEmpty)
              ReleaseLeaseCondition.WhenTimeElapsed(Deadline.now + releaseLeaseAfter)
            else
              ReleaseLeaseCondition.WhenMembersRemoved(downedNodes)
        }
      } else {
        val reverseDecision = strategy.reverseDecision(decision)
        log.info("SBR couldn't acquire lease, reverse decision [{}] to [{}]", decision, reverseDecision)
        actOnDecision(reverseDecision)
        releaseLeaseCondition = NoLease
      }

      unstashAll()
      context.become(receive)

    case ReleaseLeaseResult(_) => // superseded by new acquire release request
    case Tick                  => // ignore ticks while waiting
    case _ =>
      stash()
  }

  private def releaseLeaseResult(released: Boolean): Unit = {
    releaseLeaseCondition match {
      case ReleaseLeaseCondition.WhenTimeElapsed(deadline) =>
        if (released && deadline.isOverdue())
          releaseLeaseCondition = NoLease // released successfully
      case _ =>
      // no lease or first waiting for downed nodes to be removed
    }
  }

  /**
   * @return the nodes that were downed
   */
  def actOnDecision(decision: Decision): Set[UniqueAddress] = {
    val nodesToDown =
      try {
        strategy.nodesToDown(decision)
      } catch {
        case e: IllegalStateException =>
          log.warning(e.getMessage)
          strategy.nodesToDown(DownAll)
      }

    val downMyself = nodesToDown.contains(selfUniqueAddress)

    val indirectlyConnectedLogMessage =
      if (decision.isIndirectlyConnected)
        s", indirectly connected [${strategy.indirectlyConnected.mkString(", ")}]"
      else ""
    val unreachableDataCentersLogMessage =
      if (unreachableDataCenters.nonEmpty)
        s", unreachable DCs [${unreachableDataCenters.mkString(", ")}]"
      else ""

    log.warning(
      s"SBR took decision $decision and is downing [${nodesToDown.map(_.address).mkString(", ")}]${if (downMyself) " including myself,"
      else ""}, " +
      s"[${strategy.unreachable.size}] unreachable of [${strategy.members.size}] members" +
      indirectlyConnectedLogMessage +
      s", all members in DC [${strategy.allMembersInDC.mkString(", ")}], full reachability status: ${strategy.reachability}" +
      unreachableDataCentersLogMessage)

    if (nodesToDown.nonEmpty) {
      // downing is idempotent, and we also avoid calling down on nodes with status Down
      // down selfAddress last, since it may shutdown itself if down alone
      nodesToDown.foreach(uniqueAddress => if (uniqueAddress != selfUniqueAddress) down(uniqueAddress.address))
      if (downMyself)
        down(selfUniqueAddress.address)

      resetReachabilityChangedStats()
      resetStableDeadline()
    }
    nodesToDown
  }

  def isResponsible: Boolean = leader && selfMemberAdded

  def unreachableMember(m: Member): Unit = {
    if (m.uniqueAddress != selfUniqueAddress && m.dataCenter == selfDc) {
      log.debug("SBR unreachableMember [{}]", m)
      mutateMemberInfo(resetStable = true) { () =>
        strategy.addUnreachable(m)
        resetReachabilityChangedStatsIfAllUnreachableDowned()
      }
    }
  }

  def reachableMember(m: Member): Unit = {
    if (m.uniqueAddress != selfUniqueAddress && m.dataCenter == selfDc) {
      log.debug("SBR reachableMember [{}]", m)
      mutateMemberInfo(resetStable = true) { () =>
        strategy.addReachable(m)
        resetReachabilityChangedStatsIfAllUnreachableDowned()
      }
    }
  }

  private[sbr] def reachabilityChanged(r: Reachability): Unit = {
    if (strategy.setReachability(r)) {
      // resetStableDeadline is done from unreachableMember/reachableMember
      updateReachabilityChangedStats()
      // it may also change when members are removed and therefore the reset may be needed
      resetReachabilityChangedStatsIfAllUnreachableDowned()
      log.debug("SBR noticed {}", reachabilityChangedStats)
    }
  }

  private def updateReachabilityChangedStats(): Unit = {
    val now = System.nanoTime()
    if (reachabilityChangedStats.changeCount == 0)
      reachabilityChangedStats = ReachabilityChangedStats(now, now, 1)
    else
      reachabilityChangedStats = reachabilityChangedStats.copy(
        latestChangeTimestamp = now,
        changeCount = reachabilityChangedStats.changeCount + 1)
  }

  def unreachableDataCenter(dc: DataCenter): Unit = {
    unreachableDataCenters += dc
    log.warning(
      "Data center [{}] observed as unreachable. " +
      "Note that nodes in other data center will not be downed by SBR in this data center [{}]",
      dc,
      selfDc)
  }

  def reachableDataCenter(dc: DataCenter): Unit = {
    unreachableDataCenters -= dc
    log.info("Data center [] observed as reachable again", dc)
  }

  def seenChanged(seenBy: Set[Address]): Unit = {
    strategy.setSeenBy(seenBy)
  }

  def addUp(m: Member): Unit = {
    if (selfDc == m.dataCenter) {
      log.debug("SBR add Up [{}]", m)
      mutateMemberInfo(resetStable = true) { () =>
        strategy.add(m)
        if (m.uniqueAddress == selfUniqueAddress) mutateResponsibilityInfo { () =>
          selfMemberAdded = true
        }
      }
      strategy match {
        case s: StaticQuorum =>
          if (s.isTooManyMembers)
            log.warning(
              "The cluster size is [{}] and static-quorum.quorum-size is [{}]. You should not add " +
              "more than [{}] (static-quorum.size * 2 - 1) members to the cluster. If the exceeded cluster size " +
              "remains when a SBR decision is needed it will down all nodes.",
              s.membersWithRole.size,
              s.quorumSize,
              s.quorumSize * 2 - 1)
        case _ => // ok
      }
    }
  }

  def leaving(m: Member): Unit = {
    if (selfDc == m.dataCenter) {
      log.debug("SBR leaving [{}]", m)
      mutateMemberInfo(resetStable = false) { () =>
        strategy.add(m)
      }
    }
  }

  def addJoining(m: Member): Unit = {
    if (selfDc == m.dataCenter) {
      log.debug("SBR add Joining/WeaklyUp [{}]", m)
      strategy.add(m)
    }
  }

  def addWeaklyUp(m: Member): Unit = {
    if (m.uniqueAddress == selfUniqueAddress) mutateResponsibilityInfo { () =>
      selfMemberAdded = true
    }
    // treat WeaklyUp in same way as joining
    addJoining(m)
  }

  def remove(m: Member): Unit = {
    if (selfDc == m.dataCenter) {
      if (m.uniqueAddress == selfUniqueAddress)
        context.stop(self)
      else
        mutateMemberInfo(resetStable = false) { () =>
          log.debug("SBR remove [{}]", m)
          strategy.remove(m)

          resetReachabilityChangedStatsIfAllUnreachableDowned()

          releaseLeaseCondition = releaseLeaseCondition match {
            case ReleaseLeaseCondition.WhenMembersRemoved(downedNodes) =>
              val remainingDownedNodes = downedNodes - m.uniqueAddress
              if (remainingDownedNodes.isEmpty)
                ReleaseLeaseCondition.WhenTimeElapsed(Deadline.now + releaseLeaseAfter)
              else
                ReleaseLeaseCondition.WhenMembersRemoved(remainingDownedNodes)
            case other =>
              // no lease or not holding lease
              other
          }
        }
    }
  }

  private def releaseLease(): Unit = {
    strategy.lease.foreach { l =>
      if (releaseLeaseCondition != NoLease) {
        log.info("SBR releasing lease")
        l.release().recover { case _ => false }.map(ReleaseLeaseResult.apply).pipeTo(self)
      }
    }
  }
}
