/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import java.lang.{ Boolean => JBoolean, Integer => JInteger }

import akka.actor.ActorRef
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.Member
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardCoordinator.ActorSystemDependentAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId

import scala.collection.immutable
import scala.collection.immutable

/**
 * Common logic for the least shard allocation strategy implementations
 *
 * INTERNAL API
 */
@InternalApi
private[akka] object AbstractLeastShardAllocationStrategy {
  import MemberStatus._
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

  @volatile private var cluster: Cluster = _

  override def start(system: ActorSystem): Unit = {
    cluster = Cluster(system)
  }

  // protected for testability
  protected def selfAddress: Address = cluster.selfAddress
  protected def clusterState: CurrentClusterState = cluster.state

  final protected def isAGoodTimeToRebalance(regionEntries: Iterable[RegionEntry]): Boolean = {
    // this will filter out member with no shard regions (bc role or not yet completed joining)
    val shardRegionMembers = regionEntries.map { case RegionEntry(_, member, _) => member }
    // avoid rebalance when rolling update is in progress
    def allNodesSameVersion = shardRegionMembers.map(_.appVersion).toSet.size == 1
    // rebalance requires ack from all anyway
    def allRegionsReachable = clusterState.unreachable.diff(shardRegionMembers.toSet).isEmpty

    allNodesSameVersion && allRegionsReachable
  }

  final protected def mostSuitableRegion(
      regionEntries: Iterable[RegionEntry]): (ActorRef, immutable.IndexedSeq[ShardId]) = {
    regionEntries.toVector
      .sorted(ShardSuitabilityOrdering)
      .map { case RegionEntry(region, _, shards) => region -> shards }
      .head
  }

  final protected def regionEntriesFor(currentShardAllocations: AllocationMap): Iterable[RegionEntry] = {
    val addressToMember: Map[Address, Member] = clusterState.members.iterator.map(m => m.address -> m).toMap
    currentShardAllocations.flatMap {
      case (region, shardIds) =>
        val regionAddress = {
          if (region.path.address.hasLocalScope) selfAddress
          else region.path.address
        }

        val memberForRegion = addressToMember.get(regionAddress)
        // if the member is unknown (very unlikely but not impossible) because of view not updated yet
        // that node is ignored for this invocation
        memberForRegion.map(member => RegionEntry(region, member, shardIds))
    }
  }

}
