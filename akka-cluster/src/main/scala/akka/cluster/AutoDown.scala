/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.ConfigurationException
import akka.actor.{ Actor, ActorSystem, Address, Cancellable, Props, Scheduler }

import scala.concurrent.duration.FiniteDuration
import akka.cluster.ClusterEvent._

import scala.concurrent.duration.Duration
import akka.actor.ActorLogging

/**
 * INTERNAL API
 */
private[cluster] object AutoDown {

  def props(autoDownUnreachableAfter: FiniteDuration): Props =
    Props(classOf[AutoDown], autoDownUnreachableAfter)

  final case class UnreachableTimeout(node: UniqueAddress)
}

/**
 * Used when no custom provider is configured and 'auto-down-unreachable-after' is enabled.
 */
final class AutoDowning(system: ActorSystem) extends DowningProvider {

  private def clusterSettings = Cluster(system).settings

  override def downRemovalMargin: FiniteDuration = clusterSettings.DownRemovalMargin

  override def downingActorProps: Option[Props] =
    clusterSettings.AutoDownUnreachableAfter match {
      case d: FiniteDuration => Some(AutoDown.props(d))
      case _                 =>
        // I don't think this can actually happen
        throw new ConfigurationException(
          "AutoDowning downing provider selected but 'akka.cluster.auto-down-unreachable-after' not set")
    }
}

/**
 * INTERNAL API
 *
 * An unreachable member will be downed by this actor if it remains unreachable
 * for the specified duration and this actor is running on the leader node in the
 * cluster.
 *
 * The implementation is split into two classes AutoDown and AutoDownBase to be
 * able to unit test the logic without running cluster.
 */
private[cluster] class AutoDown(autoDownUnreachableAfter: FiniteDuration)
    extends AutoDownBase(autoDownUnreachableAfter)
    with ActorLogging {

  val cluster = Cluster(context.system)
  import cluster.ClusterLogger._

  override def selfAddress = cluster.selfAddress

  override def scheduler: Scheduler = cluster.scheduler

  // re-subscribe when restart
  override def preStart(): Unit = {
    log.warning(
      "Don't use auto-down feature of Akka Cluster in production. " +
      "See 'Auto-downing (DO NOT USE)' section of Akka Cluster documentation.")
    cluster.subscribe(self, classOf[ClusterDomainEvent])
    super.preStart()
  }
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    super.postStop()
  }

  override def down(node: Address): Unit = {
    require(leader)
    logInfo(
      "Leader is auto-downing unreachable node [{}]. " +
      "Don't use auto-down feature of Akka Cluster in production. " +
      "See 'Auto-downing (DO NOT USE)' section of Akka Cluster documentation.",
      node)
    cluster.down(node)
  }

}

/**
 * INTERNAL API
 *
 * The implementation is split into two classes AutoDown and AutoDownBase to be
 * able to unit test the logic without running cluster.
 */
private[cluster] abstract class AutoDownBase(autoDownUnreachableAfter: FiniteDuration) extends Actor {

  import AutoDown._

  def selfAddress: Address

  def down(node: Address): Unit

  def scheduler: Scheduler

  import context.dispatcher

  val skipMemberStatus = MembershipState.convergenceSkipUnreachableWithMemberStatus

  var scheduledUnreachable: Map[UniqueAddress, Cancellable] = Map.empty
  var pendingUnreachable: Set[UniqueAddress] = Set.empty
  var leader = false

  override def postStop(): Unit = {
    scheduledUnreachable.values.foreach { _.cancel }
  }

  def receive = {
    case state: CurrentClusterState =>
      leader = state.leader.exists(_ == selfAddress)
      state.unreachable.foreach(unreachableMember)

    case UnreachableMember(m) => unreachableMember(m)

    case ReachableMember(m)  => remove(m.uniqueAddress)
    case MemberRemoved(m, _) => remove(m.uniqueAddress)

    case LeaderChanged(leaderOption) =>
      leader = leaderOption.exists(_ == selfAddress)
      if (leader) {
        pendingUnreachable.foreach(node => down(node.address))
        pendingUnreachable = Set.empty
      }

    case UnreachableTimeout(node) =>
      if (scheduledUnreachable contains node) {
        scheduledUnreachable -= node
        downOrAddPending(node)
      }

    case _: ClusterDomainEvent => // not interested in other events

  }

  def unreachableMember(m: Member): Unit =
    if (!skipMemberStatus(m.status) && !scheduledUnreachable.contains(m.uniqueAddress))
      scheduleUnreachable(m.uniqueAddress)

  def scheduleUnreachable(node: UniqueAddress): Unit = {
    if (autoDownUnreachableAfter == Duration.Zero) {
      downOrAddPending(node)
    } else {
      val task = scheduler.scheduleOnce(autoDownUnreachableAfter, self, UnreachableTimeout(node))
      scheduledUnreachable += (node -> task)
    }
  }

  def downOrAddPending(node: UniqueAddress): Unit = {
    if (leader) {
      down(node.address)
    } else {
      // it's supposed to be downed by another node, current leader, but if that crash
      // a new leader must pick up these
      pendingUnreachable += node
    }
  }

  def remove(node: UniqueAddress): Unit = {
    scheduledUnreachable.get(node).foreach { _.cancel }
    scheduledUnreachable -= node
    pendingUnreachable -= node
  }

}
