/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import java.io.Closeable
import scala.collection.immutable.SortedSet

import akka.actor.{ Actor, ActorRef, ActorSystemImpl, Address, Props }
import akka.cluster.ClusterEvent._

/**
 * INTERNAL API
 *
 * Read view of cluster state, updated via subscription of
 * cluster events published on the event bus.
 */
private[akka] class ClusterReadView(cluster: Cluster) extends Closeable {

  /**
   * Current state
   */
  @volatile
  private var state: CurrentClusterState = CurrentClusterState()

  /**
   * Current internal cluster stats, updated periodically via event bus.
   */
  @volatile
  private var _latestStats = ClusterStats()

  val selfAddress = cluster.selfAddress

  // create actor that subscribes to the cluster eventBus to update current read view state
  private val eventBusListener: ActorRef = {
    cluster.system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(new Actor {
      override def preStart(): Unit = cluster.subscribe(self, classOf[ClusterDomainEvent])
      override def postStop(): Unit = cluster.unsubscribe(self)

      def receive = {
        case SeenChanged(convergence, seenBy)       ⇒ state = state.copy(convergence = convergence, seenBy = seenBy)
        case MembersChanged(members)                ⇒ state = state.copy(members = members)
        case UnreachableMembersChanged(unreachable) ⇒ state = state.copy(unreachable = unreachable)
        case LeaderChanged(leader, convergence)     ⇒ state = state.copy(leader = leader, convergence = convergence)
        case s: CurrentClusterState                 ⇒ state = s
        case CurrentInternalStats(stats)            ⇒ _latestStats = stats
        case _                                      ⇒ // ignore, not interesting
      }
    }).withDispatcher(cluster.settings.UseDispatcher), name = "clusterEventBusListener")
  }

  def self: Member = {
    state.members.find(_.address == selfAddress).orElse(state.unreachable.find(_.address == selfAddress)).
      getOrElse(Member(selfAddress, MemberStatus.Removed))
  }

  /**
   * Returns true if the cluster node is up and running, false if it is shut down.
   */
  def isRunning: Boolean = cluster.isRunning

  /**
   * Current cluster members, sorted with leader first.
   */
  def members: SortedSet[Member] = state.members

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
   * Is this node the leader?
   */
  def isLeader: Boolean = leader == Some(selfAddress)

  /**
   * Get the address of the current leader.
   */
  def leader: Option[Address] = state.leader

  /**
   * Is this node a singleton cluster?
   */
  def isSingletonCluster: Boolean = members.size == 1

  /**
   * Checks if we have a cluster convergence.
   */
  def convergence: Boolean = state.convergence

  /**
   * Returns true if the node is UP or JOINING.
   */
  def isAvailable: Boolean = {
    val myself = self
    !unreachableMembers.contains(myself) && !myself.status.isUnavailable
  }

  /**
   * INTERNAL API
   * The nodes that has seen current version of the Gossip.
   */
  private[cluster] def seenBy: Set[Address] = state.seenBy

  /**
   * INTERNAL API
   */
  private[cluster] def latestStats: ClusterStats = _latestStats

  /**
   * Unsubscribe to cluster events.
   */
  def close(): Unit = {
    cluster.system.stop(eventBusListener)
  }

}