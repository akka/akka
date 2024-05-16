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

    // FIXME take a look at ShardSuitabilityOrdering for member status and appVersion preference

    Future.successful(regionWithNeighbor.getOrElse(allocateWithoutNeighbor(slice, currentShardAllocations)))
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

    // This is the same rebalance algorithm as in LeastShardAllocationStrategy, but it picks shards
    // in s different preferred order

    // FIXME I think this algo is too exact, expecting just 1 diff from optimal.
    // FIXME Risk that the rebalanced shards end up at the same place and we have a loop without value.

    def limit(numberOfShards: Int): Int =
      max(1, min((relativeLimit * numberOfShards).toInt, absoluteLimit))

    def rebalancePhase1(
        numberOfShards: Int,
        optimalPerRegion: Int,
        sortedEntries: Iterable[RegionEntry]): Set[ShardId] = {
      val selected = Vector.newBuilder[ShardId]
      sortedEntries.foreach {
        case RegionEntry(_, _, shardIds) =>
          if (shardIds.size > optimalPerRegion) {
            // Since we prefer contiguous ranges we pick shards to rebalance from lower and upper slices
            val sortedShardIds = shardIds.sortBy(_.toInt)
            val (lowerHalf, upperHalf) = sortedShardIds.iterator.splitAt(sortedShardIds.size / 2)
            val preferredOrder = lowerHalf.zipAll(upperHalf.toList.reverse, "", "").flatMap {
              case (a, "") => a :: Nil
              case ("", b) => b :: Nil
              case (a, b)  => a :: b :: Nil
            }

            selected ++= preferredOrder.take(shardIds.size - optimalPerRegion)
          }
      }
      val result = selected.result()
      result.take(limit(numberOfShards)).toSet
    }

    def rebalancePhase2(
        numberOfShards: Int,
        optimalPerRegion: Int,
        sortedEntries: Iterable[RegionEntry]): Future[Set[ShardId]] = {
      // In the first phase the optimalPerRegion is rounded up, and depending on number of shards per region and number
      // of regions that might not be the exact optimal.
      // In second phase we look for diff of >= 2 below optimalPerRegion and rebalance that number of shards.
      val countBelowOptimal =
        sortedEntries.iterator.map(entry => max(0, (optimalPerRegion - 1) - entry.shardIds.size)).sum
      if (countBelowOptimal == 0) {
        emptyRebalanceResult
      } else {
        val selected = Vector.newBuilder[ShardId]
        sortedEntries.foreach {
          case RegionEntry(_, _, shardIds) =>
            if (shardIds.size >= optimalPerRegion) {
              selected += shardIds.maxBy(_.toInt)
            }
        }
        val result = selected.result().take(min(countBelowOptimal, limit(numberOfShards))).toSet
        Future.successful(result)
      }
    }

    if (rebalanceInProgress.nonEmpty) {
      // one rebalance at a time
      emptyRebalanceResult
    } else {
      val sortedRegionEntries = regionEntriesFor(currentShardAllocations).toVector.sorted(ShardSuitabilityOrdering)
      if (!isAGoodTimeToRebalance(sortedRegionEntries)) {
        emptyRebalanceResult
      } else {
        val numberOfShards = sortedRegionEntries.map(_.shardIds.size).sum
        val numberOfRegions = sortedRegionEntries.size
        if (numberOfRegions == 0 || numberOfShards == 0) {
          emptyRebalanceResult
        } else {
          val optimalPerRegion = numberOfShards / numberOfRegions + (if (numberOfShards % numberOfRegions == 0) 0
                                                                     else 1)

          val result1 = rebalancePhase1(numberOfShards, optimalPerRegion, sortedRegionEntries)

          if (result1.nonEmpty) {
            Future.successful(result1)
          } else {
            rebalancePhase2(numberOfShards, optimalPerRegion, sortedRegionEntries)
          }
        }
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

}
