/*
 * Copyright (C) 2024-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.Member
import akka.cluster.sharding.ShardCoordinator.ActorSystemDependentAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.internal.ClusterShardAllocationMixin
import akka.cluster.sharding.internal.ClusterShardAllocationMixin.RegionEntry
import akka.cluster.sharding.internal.ClusterShardAllocationMixin.ShardSuitabilityOrdering

object SliceRangeShardAllocationStrategy {
  // Not using Persistence because akka-persistence dependency could be optional
  private val NumberOfSlices = 1024

  private val emptyRebalanceResult = Future.successful(Set.empty[ShardId])

  final class ShardBySliceMessageExtractor // FIXME
}

/**
 * It will not rebalance when there is already an ongoing rebalance in progress.
 *
 * @param absoluteLimit the maximum number of shards that will be rebalanced in one rebalance round
 * @param relativeLimit fraction (< 1.0) of total number of (known) shards that will be rebalanced
 *                      in one rebalance round
 */
class SliceRangeShardAllocationStrategy(absoluteLimit: Int, relativeLimit: Double)
    extends ActorSystemDependentAllocationStrategy
    with ClusterShardAllocationMixin {
  import SliceRangeShardAllocationStrategy._

  private var cluster: Cluster = _

  override def start(system: ActorSystem): Unit = {
    cluster = Cluster(system)
  }

  override protected def clusterState: CurrentClusterState = cluster.state
  override protected def selfMember: Member = cluster.selfMember

  private var previousRebalance = Set.empty[ShardId]

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]]): Future[ActorRef] = {
    val slice = shardId.toInt
    if (slice >= NumberOfSlices)
      throw new IllegalArgumentException("slice must be between 0 and 1023. Use `ShardBySliceMessageExtractor`.")

    // allow some overfill, rebalance will try to move from the lower/upper slices
    val overfill = 2
    val maxShards = (NumberOfSlices / currentShardAllocations.size) + overfill

    // FIXME take a look at ShardSuitabilityOrdering for member status and appVersion preference

    findRegionWithNeighbor(slice, maxShards, currentShardAllocations) match {
      case Some(regionWithNeighbor) =>
        val neighborShards = currentShardAllocations(regionWithNeighbor)
        if (neighborShards.size >= NumberOfSlices / currentShardAllocations.size - 2) {
          // close to max slices, if the slice is at the boundary, look for a region with lower/upper neighbor slice
          // and compare that as alternative, use the one with least number of slices
          val neighborSlices = neighborShards.map(_.toInt)
          val alternative =
            if (neighborSlices.min > slice)
              findRegionWithLowerNeighbor(slice, maxShards, currentShardAllocations)
            else if (neighborSlices.max < slice)
              findRegionWithUpperNeighbor(slice, maxShards, currentShardAllocations)
            else
              None

          val selectedRegion =
            alternative match {
              case Some(alternativeRegion) =>
                if (neighborShards.size >= currentShardAllocations(alternativeRegion).size)
                  regionWithNeighbor
                else
                  alternativeRegion
              case None =>
                regionWithNeighbor
            }
          Future.successful(selectedRegion)

        } else {
          Future.successful(regionWithNeighbor)
        }
      case None =>
        Future.successful(allocateWithoutNeighbor(slice, currentShardAllocations))
    }
  }

  private def allocateWithoutNeighbor(
      slice: Int,
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]]): ActorRef = {

    // sort regions by member age because if a node is added (random address) we don't want rebalance more than necessary
    val regionsByMbr = regionEntriesFor(currentShardAllocations).map(entry => entry.member -> entry.region).toMap
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
    import math.max
    import math.min

    def limit(numberOfShards: Int): Int =
      max(1, min((relativeLimit * numberOfShards).toInt, absoluteLimit))

    if (rebalanceInProgress.nonEmpty) {
      // one rebalance at a time
      emptyRebalanceResult
    } else {
      val sortedRegionEntries = regionEntriesFor(currentShardAllocations).toVector.sorted(ShardSuitabilityOrdering)
      if (!isAGoodTimeToRebalance(sortedRegionEntries)) {
        emptyRebalanceResult
      } else {
        // this is the number of slices per region that we are aiming for
        val overfill = 1
        val targetSize = NumberOfSlices / sortedRegionEntries.size + overfill
        val selected = Vector.newBuilder[ShardId]
        // FIXME ShardSuitabilityOrdering isn't used, but it seems better to use most shards first, combine them?
        sortedRegionEntries.sortBy(entry => 0 - entry.shardIds.size).foreach {
          case RegionEntry(_, _, shards) =>
            if (shards.size > targetSize) {
              // Skip shards that were rebalanced in previous rounds to avoid loop of rebalance-allocate
              // to same regions.
              // Since we prefer contiguous ranges we pick shards to rebalance from lower and upper slices
              val sortedShardIds = shards.filterNot(previousRebalance.contains).sortBy(_.toInt)
              val (lowerHalf, upperHalf) = sortedShardIds.iterator.splitAt(sortedShardIds.size / 2)
              val preferredOrder = lowerHalf.zipAll(upperHalf.toList.reverse, "", "").flatMap {
                case (a, "") => a :: Nil
                case ("", b) => b :: Nil
                case (a, b)  => a :: b :: Nil
              }

              selected ++= preferredOrder.take(shards.size - targetSize)
            }
        }
        val result = selected.result()
        val currentNumberOfShards = sortedRegionEntries.map(_.shardIds.size).sum
        val limitedResult = result.take(limit(currentNumberOfShards)).toSet
        previousRebalance = previousRebalance.union(limitedResult)
        if (previousRebalance.size >= 100)
          previousRebalance = Set.empty[ShardId] // start over
        Future.successful(limitedResult)
      }
    }

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

  private def findRegionWithLowerNeighbor(
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
            find(delta + 1)
        }
      }
    }

    find(delta = 1)
  }

  private def findRegionWithUpperNeighbor(
      slice: Int,
      maxShards: Int,
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Option[ActorRef] = {
    val maxDelta = 10

    @tailrec def find(delta: Int): Option[ActorRef] = {
      if (delta == maxDelta)
        None
      else {
        findRegionWithNeighbor(slice, delta, maxShards, currentShardAllocations) match {
          case found @ Some(_) => found
          case None =>
            find(delta + 1)
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
    val neighborSlice = slice + diff
    if (neighborSlice < 0 || neighborSlice > NumberOfSlices - 1)
      None
    else {
      val neighbor = (slice + diff).toString
      currentShardAllocations.collectFirst {
        case (region, shards) if shards.contains(neighbor) && shards.size < maxShards =>
          region
      }
    }
  }

}
