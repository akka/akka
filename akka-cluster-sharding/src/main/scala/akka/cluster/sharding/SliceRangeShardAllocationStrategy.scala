/*
 * Copyright (C) 2024-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.annotation.tailrec
import scala.collection.immutable
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

    val overfill = 1 // allow some overfill
    val maxShards = (NumberOfSlices / currentShardAllocations.size) + overfill
    val regionWithNeighbor = findRegionWithNeighbor(slice, maxShards, currentShardAllocations)

    Future.successful(regionWithNeighbor.getOrElse(allocateWithoutNeighbor(slice, currentShardAllocations)))
  }

  private def allocateWithoutNeighbor(
      slice: Int,
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]]): ActorRef = {

    // sort regions by member age because if a node is added (random address) we don't want rebalance more than necessary
    val regionsByMbr = regionsByMember(currentShardAllocations.keySet)
    val regions = regionsByMbr.keysIterator.toIndexedSeq.sorted(Member.ageOrdering).map(regionsByMbr(_))

    // first look for an empty region (e.g. rolling update case)
    regions.reverse.collectFirst { case region if currentShardAllocations(region).isEmpty => region } match {
      case Some(emptyRegion) => emptyRegion
      case None =>
        val rangeSize = NumberOfSlices / regions.size
        val i = slice / rangeSize

        if (i < regions.size) {
          regions(i)
        } else {
          // This covers the rounding case for the last region, which we just distribute over all regions.
          // May also happen if member for that region has been removed, but that should be a rare case.
          regions(slice % regions.size)
        }
    }

  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {
    println(s"# $rebalanceLimit") // FIXME
    ???
  }

  private def findRegionWithNeighbor(
      slice: Int,
      maxShards: Int,
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Option[ActorRef] = {
    val maxDelta = 10

    @tailrec def find(delta: Int): Option[ActorRef] = {
      if (delta == maxDelta)
        None
      else {
        findRegionWithNeighbor(slice, -delta, maxShards, currentShardAllocations) match {
          case found @ Some(_) => found
          case None =>
            findRegionWithNeighbor(slice, delta, maxShards, currentShardAllocations) match {
              case found @ Some(_) => found
              case None =>
                find(delta + 1)
            }
        }
      }
    }

    find(delta = 1)
  }

  private def findRegionWithNeighbor(
      slice: Int,
      diff: Int,
      maxShards: Int,
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Option[ActorRef] = {
    if (diff < 0 && slice <= 0)
      None
    else if (diff > 0 && slice >= NumberOfSlices - 1)
      None
    else {
      val neighbor = (slice + diff).toString
      currentShardAllocations.collectFirst {
        case (region, shards) if shards.contains(neighbor) && shards.size < maxShards =>
          region
      }
    }
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
