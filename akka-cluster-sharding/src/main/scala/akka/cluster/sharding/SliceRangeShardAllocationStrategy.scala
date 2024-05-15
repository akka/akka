/*
 * Copyright (C) 2024-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.Future

import akka.actor.ActorRef
import akka.actor.ActorRefScope
import akka.actor.ActorSystem
import akka.actor.MinimalActorRef
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.Member
import akka.cluster.sharding.ShardCoordinator.ActorSystemDependentAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.internal.ClusterShardAllocationMixin

object SliceRangeShardAllocationStrategy {
  // Not using Persistence because akka-persistence dependency could be optional
  val NumberOfSlices = 1024

  final class ShardBySliceMessageExtractor // FIXME
}

class SliceRangeShardAllocationStrategy(rebalanceLimit: Int)
    extends ActorSystemDependentAllocationStrategy
    with ClusterShardAllocationMixin {
  import SliceRangeShardAllocationStrategy._

  private var cluster: Cluster = _

  override def start(system: ActorSystem): Unit = {
    cluster = Cluster(system)
  }

  override protected def clusterState: CurrentClusterState = cluster.state
  override protected def selfMember: Member = cluster.selfMember

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]]): Future[ActorRef] = {
    val slice = shardId.toInt
    if (slice >= NumberOfSlices)
      throw new IllegalArgumentException("slice must be between 0 and 1023. Use `ShardBySliceMessageExtractor`.")

    // sort regions by member age because if a node is added (random address) we don't want rebalance more than necessary
    val regionsByMbr = regionsByMember(currentShardAllocations.keySet)
    val regions = regionsByMbr.keysIterator.toIndexedSeq.sorted(Member.ageOrdering).map(regionsByMbr(_))
    val rangeSize = NumberOfSlices / regions.size
    val i = slice / rangeSize

    val selectedRegion =
      if (i < regions.size) {
        regions(i)
      } else {
        // This covers the rounding case for the last region, which we just distribute over all regions.
        // May also happen if member for that region has been removed, but that should be a rare case.
        regions(slice % regions.size)
      }

    Future.successful(selectedRegion)
  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {
    println(s"# $rebalanceLimit") // FIXME
    ???
  }

  def regionsByMember(regions: Set[ActorRef]): Map[Member, ActorRef] = {
    val membersByAddress = clusterState.members.iterator.map(m => m.address -> m).toMap
    regions.iterator.flatMap { ref =>
      ref match {
        case refScope: ActorRefScope if refScope.isLocal && !ref.isInstanceOf[MinimalActorRef] =>
          // test is using MinimalActorRef
          Some(selfMember -> ref)
        case _ =>
          membersByAddress.get(ref.path.address).map(_ -> ref)
      }
    }.toMap
  }

}
