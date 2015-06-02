/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.actor.Actor
import akka.actor.Address
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.cluster.ClusterEvent._
import akka.actor.ActorLogging

/**
 * INTERNAL API
 */
private[akka] object SplitBrainResolver {

  def props(stableAfter: FiniteDuration, strategy: Strategy): Props =
    Props(classOf[SplitBrainResolver], stableAfter, strategy)

  final case object Tick

  final val KeepMajorityName = "keep-majority"
  final val StaticQuorumName = "static-quorum"
  final val KeepOldestName = "keep-oldest"
  final val KeepRefereeName = "keep-referee"

  sealed trait Decision
  case object DownReachable extends Decision
  case object DownUnreachable extends Decision
  case object DownAll extends Decision

  abstract class Strategy extends NoSerializationVerificationNeeded {
    // may contain joining
    var unreachable: Set[UniqueAddress] = Set.empty[UniqueAddress]

    protected def ordering: Ordering[Member] = Member.ordering
    private var _members: immutable.SortedSet[Member] = immutable.SortedSet.empty(ordering)
    // all members, but doesn't contain joining
    def members: immutable.SortedSet[Member] = _members

    // all nodes, but doesn't contain joining
    def nodes: Set[UniqueAddress] = members.map(_.uniqueAddress)

    def reachableNodes: Set[UniqueAddress] =
      members.collect {
        case m if !unreachable(m.uniqueAddress) ⇒ m.uniqueAddress
      }

    def role: Option[String]

    def unreachableMembers: immutable.SortedSet[Member] = members.filter(m ⇒ unreachable(m.uniqueAddress))

    def membersWithRole: immutable.SortedSet[Member] = role match {
      case None    ⇒ members
      case Some(r) ⇒ members.filter(_.hasRole(r))
    }

    def unreachableMembersWithRole: immutable.SortedSet[Member] = role match {
      case None    ⇒ unreachableMembers
      case Some(r) ⇒ members.filter(m ⇒ unreachable(m.uniqueAddress) && m.hasRole(r))
    }

    def add(m: Member): Unit =
      _members = members - m + m // replace, equals is using the address

    def remove(m: Member): Unit =
      _members -= m

    def decide(): Decision

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
   */
  class StaticQuorum(val quorumSize: Int, override val role: Option[String]) extends Strategy {
    override def decide(): Decision =
      if (membersWithRole.size - unreachableMembersWithRole.size >= quorumSize)
        DownUnreachable
      else
        DownReachable
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
   */
  class KeepMajority(override val role: Option[String]) extends Strategy {
    override def decide(): Decision = {
      val ms = membersWithRole
      if (ms.isEmpty)
        DownAll // no node with matching role
      else {
        val unreachableSize = unreachableMembersWithRole.size
        val membersSize = ms.size

        if (unreachableSize * 2 == membersSize) {
          // equal size, keep the side with the lowest address (first in members)
          if (unreachable(ms.head.uniqueAddress)) DownReachable else DownUnreachable
        } else if (unreachableSize * 2 < membersSize) {
          // we are in majority
          DownUnreachable
        } else {
          // we are in minority
          DownReachable
        }
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
   */
  class KeepOldest(val downIfAlone: Boolean, override val role: Option[String]) extends Strategy {

    // sort by age, oldest first
    override def ordering = Ordering.fromLessThan[Member](_ isOlderThan _)

    override def decide(): Decision = {
      val ms = membersWithRole
      if (ms.isEmpty)
        DownAll // no node with matching role
      else {
        val oldest = ms.head.uniqueAddress
        val oldestIsReachable = !unreachable(oldest)
        val reachableCount = ms.count(m ⇒ !unreachable(m.uniqueAddress))
        val unreachableCount = ms.size - reachableCount
        if (oldestIsReachable) {
          // if there are only 2 nodes in the cluster it is better to keep the oldest, even though it is alone
          // E.g. 2 nodes: reachableCount=1, unreachableCount=1 => DownUnreachable, i.e. keep the oldest
          //               even though it is alone (because the node on the other side is no better)
          // E.g. 3 nodes: reachableCount=1, unreachableCount=2 => DownReachable, i.e. shut down the
          //               oldest because it is alone
          if (downIfAlone && reachableCount == 1 && unreachableCount >= 2) DownReachable
          else DownUnreachable
        } else {
          if (downIfAlone && unreachableCount == 1 && reachableCount >= 2) DownUnreachable
          else DownReachable
        }
      }
    }
  }

  /**
   * Down the part that does not contain the given referee node.
   * If the remaining number of nodes are less than the given `downAllIfLessThanNodes`
   * all nodes will be downed.
   * If the referee node itself is removed all nodes will be downed.
   */
  class KeepReferee(val referee: Address, val downAllIfLessThanNodes: Int) extends Strategy {

    override def decide(): Decision = {
      val unrMbrs = unreachableMembers
      if (unrMbrs.exists(_.address == referee)) {
        // the referee is in the unreachable set
        if (unrMbrs.size >= downAllIfLessThanNodes) DownReachable else DownAll
      } else if (members.exists(_.address == referee)) {
        // the referee is in the reachable set
        if (members.size - unrMbrs.size >= downAllIfLessThanNodes) DownUnreachable else DownAll
      } else
        DownAll // referee was removed => down all
    }

    override def role = None
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
  import SplitBrainResolver._

  val cluster = Cluster(context.system)
  import cluster.InfoLogger._

  override def selfUniqueAddress = cluster.selfUniqueAddress

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
    require(leader)
    cluster.down(node)
  }

  override def joining: Set[UniqueAddress] = cluster.state.members.collect {
    case m if m.status == MemberStatus.Joining ⇒ m.uniqueAddress
  }

}

/**
 * INTERNAL API
 *
 * The implementation is split into two classes SplitBrainResolver and SplitBrainResolverBase to be
 * able to unit test the logic without running cluster.
 */
private[akka] abstract class SplitBrainResolverBase(stableAfter: FiniteDuration, strategy: SplitBrainResolver.Strategy)
  extends Actor with ActorLogging {

  import SplitBrainResolver._

  def selfUniqueAddress: UniqueAddress

  def down(node: Address): Unit

  def joining: Set[UniqueAddress]

  import context.dispatcher
  val tickTask = {
    val interval = (stableAfter / 2).max(500.millis)
    context.system.scheduler.schedule(interval, interval / 2, self, Tick)
  }

  val skipMemberStatus = Gossip.convergenceSkipUnreachableWithMemberStatus

  var leader = false
  var selfMemberAdded = false

  def newStableDeadline(): Deadline = Deadline.now + stableAfter
  var stableDeadline = newStableDeadline()

  override def postStop(): Unit = {
    tickTask.cancel()
    super.postStop()
  }

  def receive = {
    case UnreachableMember(m) ⇒ unreachableMember(m)
    case ReachableMember(m)   ⇒ reachableMember(m)
    case MemberUp(m)          ⇒ add(m)
    case MemberRemoved(m, _)  ⇒ remove(m)

    case LeaderChanged(leaderOption) ⇒
      leader = leaderOption.exists(_ == selfUniqueAddress.address)

    case Tick ⇒
      if (leader && selfMemberAdded && strategy.unreachable.nonEmpty && stableDeadline.isOverdue()) {

        val nodesToDown = strategy.decide() match {
          case DownUnreachable ⇒ strategy.unreachable
          case DownReachable   ⇒ strategy.reachableNodes ++ joining.filterNot(strategy.unreachable)
          case DownAll         ⇒ strategy.nodes ++ joining
        }
        if (nodesToDown.nonEmpty) {
          // important that down is idempotent

          val downMyself = nodesToDown.contains(selfUniqueAddress)
          log.info("Downing [{}]{}, [{}] unreachable of [{}] members", nodesToDown.map(_.address).mkString(", "),
            if (downMyself) ", including myself" else "", strategy.unreachable.size, strategy.members.size)
          // down selfAddress last, since it may shutdown itself if down alone
          nodesToDown.foreach(node ⇒ if (node != selfUniqueAddress) down(node.address))
          if (downMyself)
            down(selfUniqueAddress.address)
          stableDeadline = newStableDeadline()
        }
      }

    case _: ClusterDomainEvent ⇒ // not interested in other events

  }

  def unreachableMember(m: Member): Unit = {
    require(m.uniqueAddress != selfUniqueAddress, "selfAddress cannot be unreachable")
    // skip Down and Exiting nodes, they are already on their way to be removed
    if (!skipMemberStatus(m.status)) {
      strategy.unreachable += m.uniqueAddress
      if (m.status != MemberStatus.Joining)
        strategy.add(m)
    }
    stableDeadline = newStableDeadline()
  }

  def reachableMember(m: Member): Unit = {
    strategy.unreachable -= m.uniqueAddress
    stableDeadline = newStableDeadline()
  }

  def add(m: Member): Unit = {
    strategy.add(m)
    if (m.uniqueAddress == selfUniqueAddress)
      selfMemberAdded = true
    stableDeadline = newStableDeadline()
    strategy match {
      case s: StaticQuorum ⇒
        if (s.membersWithRole.size > (s.quorumSize * 2 - 1))
          log.warning("The cluster size is [{}] and static-quorum.quorum-size is [{}]. You must not add more than [{}] " +
            "(static-quorum.size * 2 - 1) members to the cluster, because then both sides may down each " +
            "other and thereby form two separate clusters.", s.membersWithRole.size, s.quorumSize, s.quorumSize * 2 - 1)
      case _ ⇒ // ok
    }
  }

  def remove(m: Member): Unit =
    if (m.uniqueAddress == selfUniqueAddress)
      context.stop(self)
    else {
      strategy.unreachable -= m.uniqueAddress
      strategy.remove(m)
      stableDeadline = newStableDeadline()
    }

}

