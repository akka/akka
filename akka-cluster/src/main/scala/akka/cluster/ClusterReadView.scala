/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import java.io.Closeable

import scala.collection.immutable

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Deploy
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.ClusterEvent._
import akka.dispatch.RequiresMessageQueue
import akka.dispatch.UnboundedMessageQueueSemantics

/**
 * INTERNAL API
 *
 * Read view of cluster state, updated via subscription of
 * cluster events published on the event bus.
 */
private[akka] class ClusterReadView(cluster: Cluster) extends Closeable {
  import cluster.ClusterLogger._

  /**
   * Current state
   */
  @volatile
  private var _state: CurrentClusterState = CurrentClusterState()

  @volatile
  private var _reachability: Reachability = Reachability.empty

  @volatile
  private var _cachedSelf: Member =
    Member(cluster.selfUniqueAddress, cluster.selfRoles).copy(status = MemberStatus.Removed)
  @volatile
  private var _closed: Boolean = false

  /**
   * Current internal cluster stats, updated periodically via event bus.
   */
  @volatile
  private var _latestStats = CurrentInternalStats(GossipStats(), VectorClockStats())

  val selfAddress: Address = cluster.selfAddress

  // create actor that subscribes to the cluster eventBus to update current read view state
  private val eventBusListener: ActorRef = {
    cluster.system
      .systemActorOf(Props(new Actor with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
        override def preStart(): Unit = cluster.subscribe(self, classOf[ClusterDomainEvent])

        def receive: Receive = {
          case e: ClusterDomainEvent if !_closed =>
            e match {
              case SeenChanged(_, seenBy) =>
                _state = _state.copy(seenBy = seenBy)
              case ReachabilityChanged(reachability) =>
                _reachability = reachability
              case MemberRemoved(member, _) =>
                _state = _state.copy(members = _state.members - member, unreachable = _state.unreachable - member)
              case UnreachableMember(member) =>
                // replace current member with new member (might have different status, only address is used in equals)
                _state = _state.copy(unreachable = _state.unreachable - member + member)
              case ReachableMember(member) =>
                _state = _state.copy(unreachable = _state.unreachable - member)
              case event: MemberEvent =>
                // replace current member with new member (might have different status, only address is used in equals)
                val newUnreachable =
                  if (_state.unreachable.contains(event.member)) _state.unreachable - event.member + event.member
                  else _state.unreachable
                _state =
                  _state.copy(members = _state.members - event.member + event.member, unreachable = newUnreachable)
              case LeaderChanged(leader) =>
                _state = _state.copy(leader = leader)
              case RoleLeaderChanged(role, leader) =>
                _state = _state.copy(roleLeaderMap = _state.roleLeaderMap + (role -> leader))
              case stats: CurrentInternalStats => _latestStats = stats
              case ClusterShuttingDown         =>
              case r: ReachableDataCenter =>
                _state = _state.withUnreachableDataCenters(_state.unreachableDataCenters - r.dataCenter)
              case r: UnreachableDataCenter =>
                _state = _state.withUnreachableDataCenters(_state.unreachableDataCenters + r.dataCenter)

            }

            e match {
              case e: MemberEvent if e.member.address == selfAddress =>
                _cachedSelf = e.member
              case _ =>
            }

            // once captured, optional verbose logging of event
            e match {
              case _: SeenChanged => // ignore
              case event =>
                if (cluster.settings.LogInfoVerbose)
                  logInfo("event {}", event)
            }

          case s: CurrentClusterState if !_closed =>
            _state = s
            _cachedSelf = s.members.find(_.uniqueAddress == cluster.selfUniqueAddress).getOrElse(_cachedSelf)
        }
      }).withDispatcher(cluster.settings.UseDispatcher).withDeploy(Deploy.local), name = "clusterEventBusListener")
  }

  def state: CurrentClusterState = _state

  def self: Member = _cachedSelf

  /**
   * Returns true if this cluster instance has be shutdown.
   */
  def isTerminated: Boolean = cluster.isTerminated

  /**
   * Current cluster members, sorted by address.
   */
  def members: immutable.SortedSet[Member] = state.members

  /**
   * Members that has been detected as unreachable.
   */
  def unreachableMembers: Set[Member] = state.unreachable

  /**
   * Member status for this node ([[akka.cluster.MemberStatus]]).
   *
   * NOTE: If the node has been removed from the cluster (and shut down) then it's status is set to the 'REMOVED' tombstone state
   *       and is no longer present in the node ring or any other part of the gossiping state. However in order to maintain the
   *       model and the semantics the user would expect, this method will in this situation return `MemberStatus.Removed`.
   */
  def status: MemberStatus = self.status

  /**
   * Is this node the current data center leader
   */
  def isLeader: Boolean = leader.contains(selfAddress)

  /**
   * Get the address of the current data center leader
   */
  def leader: Option[Address] = state.leader

  /**
   * Does the cluster consist of only one member?
   */
  def isSingletonCluster: Boolean = members.size == 1

  /**
   * Returns true if the node is not unreachable and not `Down`
   * and not `Removed`.
   */
  def isAvailable: Boolean = {
    val myself = self
    !unreachableMembers.contains(myself) &&
    myself.status != MemberStatus.Down &&
    myself.status != MemberStatus.Removed
  }

  def reachability: Reachability = _reachability

  /**
   * INTERNAL API
   * The nodes that has seen current version of the Gossip.
   */
  private[cluster] def seenBy: Set[Address] = state.seenBy

  /**
   * INTERNAL API
   */
  private[cluster] def latestStats: CurrentInternalStats = _latestStats

  /**
   * Unsubscribe to cluster events.
   */
  def close(): Unit = {
    _closed = true
    _cachedSelf = self.copy(MemberStatus.Removed)
    if (!eventBusListener.isTerminated)
      eventBusListener ! PoisonPill
  }

}
