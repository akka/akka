/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import java.lang.{ Boolean => JBoolean, Integer => JInteger }

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardCoordinator.ActorSystemDependentAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId
import akka.pattern.after

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * Common logic for the least shard allocation strategy implementations
 *
 * INTERNAL API
 */
@InternalApi
private[akka] object AbstractLeastShardAllocationStrategy {
  import MemberStatus._
  private val JoiningCluster: Set[MemberStatus] = Set(Joining, WeaklyUp)
  private val LeavingClusterStatuses: Set[MemberStatus] = Set(Leaving, Exiting, Down)

  type AllocationMap = Map[ActorRef, immutable.IndexedSeq[ShardId]]

  final case class RegionEntry(region: ActorRef, member: Member, shardIds: immutable.IndexedSeq[ShardId])

  implicit object ShardSuitabilityOrdering extends Ordering[RegionEntry] {
    override def compare(x: RegionEntry, y: RegionEntry): Int = {
      val RegionEntry(_, memberX, allocatedShardsX) = x
      val RegionEntry(_, memberY, allocatedShardsY) = y
      if (memberX.status != memberY.status) {
        // prefer allocating to nodes that are not on their way out of the cluster
        val xIsLeaving = LeavingClusterStatuses(memberX.status)
        val yIsLeaving = LeavingClusterStatuses(memberY.status)
        JBoolean.compare(xIsLeaving, yIsLeaving)
      } else if (memberX.appVersion != memberY.appVersion) {
        // prefer nodes with the highest rolling update app version
        memberY.appVersion.compareTo(memberX.appVersion)
      } else {
        // prefer the node with the least allocated shards
        JInteger.compare(allocatedShardsX.size, allocatedShardsY.size)
      }
    }
  }
}

/**

 *
 * INTERNAL API
 */
@InternalApi
private[akka] abstract class AbstractLeastShardAllocationStrategy extends ActorSystemDependentAllocationStrategy {
  import AbstractLeastShardAllocationStrategy._

  @volatile private var system: ActorSystem = _
  @volatile private var cluster: Cluster = _

  override def start(system: ActorSystem): Unit = {
    this.system = system
    cluster = Cluster(system)
  }

  // protected for testability
  protected def clusterState: CurrentClusterState = cluster.state
  protected def selfMember: Member = cluster.selfMember

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef] = {
    val regionEntries = regionEntriesFor(currentShardAllocations)
    if (regionEntries.isEmpty) {
      // very unlikely to ever happen but possible because of cluster state view not yet updated when collecting
      // region entries, view should be updated after a very short time
      after(50.millis)(allocateShard(requester, shardId, currentShardAllocations))(system)
    } else {
      val (region, _) = mostSuitableRegion(regionEntries)
      Future.successful(region)
    }
  }

  final protected def isAGoodTimeToRebalance(regionEntries: Iterable[RegionEntry]): Boolean = {
    // Avoid rebalance when rolling update is in progress
    // (This will ignore versions on members with no shard regions, because of sharding role or not yet completed joining)
    regionEntries.headOption match {
      case None => false // empty list of regions, probably not a good time to rebalance...
      case Some(firstRegion) =>
        def allNodesSameVersion = {
          regionEntries.forall(_.member.appVersion == firstRegion.member.appVersion)
        }
        // Rebalance requires ack from regions and proxies - no need to rebalance if it cannot be completed
        // FIXME #29589, we currently only look at same dc but proxies in other dcs may delay complete as well right now
        def neededMembersReachable =
          !clusterState.members.exists(m => m.dataCenter == selfMember.dataCenter && clusterState.unreachable(m))
        // No members in same dc joining, we want that to complete before rebalance, such nodes should reach Up soon
        def membersInProgressOfJoining =
          clusterState.members.exists(m => m.dataCenter == selfMember.dataCenter && JoiningCluster(m.status))

        allNodesSameVersion && neededMembersReachable && !membersInProgressOfJoining
    }
  }

  final protected def mostSuitableRegion(
      regionEntries: Iterable[RegionEntry]): (ActorRef, immutable.IndexedSeq[ShardId]) = {
    val mostSuitableEntry = regionEntries.min(ShardSuitabilityOrdering)
    mostSuitableEntry.region -> mostSuitableEntry.shardIds
  }

  final protected def regionEntriesFor(currentShardAllocations: AllocationMap): Iterable[RegionEntry] = {
    val addressToMember: Map[Address, Member] = clusterState.members.iterator.map(m => m.address -> m).toMap
    currentShardAllocations.flatMap {
      case (region, shardIds) =>
        val regionAddress = {
          if (region.path.address.hasLocalScope) selfMember.address
          else region.path.address
        }

        val memberForRegion = addressToMember.get(regionAddress)
        // if the member is unknown (very unlikely but not impossible) because of view not updated yet
        // that node is ignored for this invocation
        memberForRegion.map(member => RegionEntry(region, member, shardIds))
    }
  }

}
