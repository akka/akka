/**
 * Copyright (C) 2015-2020 Lightbend Inc.  <https://www.lightbend.com>
 */

package akka.cluster.sbr

import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster._
import akka.pattern.pipe

/**
 * INTERNAL API
 */
private[akka] object SplitBrainResolver {

  def props(stableAfter: FiniteDuration, strategy: DowningStrategy): Props =
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
private[akka] class SplitBrainResolver(stableAfter: FiniteDuration, strategy: DowningStrategy)
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
private[akka] abstract class SplitBrainResolverBase(stableAfter: FiniteDuration, strategy: DowningStrategy)
    extends Actor
    with ActorLogging
    with Stash {

  import DowningStrategy._
  import SplitBrainResolver.ReleaseLeaseCondition.NoLease
  import SplitBrainResolver._

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
