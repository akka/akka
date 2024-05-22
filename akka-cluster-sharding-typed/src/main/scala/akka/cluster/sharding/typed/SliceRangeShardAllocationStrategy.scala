/*
 * Copyright (C) 2024-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import scala.annotation.tailrec
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
import akka.persistence.Persistence
import akka.persistence.typed.PersistenceId

object SliceRangeShardAllocationStrategy {
  private val emptyRebalanceResult = Future.successful(Set.empty[ShardId])

  final class ShardBySliceMessageExtractor[M](entityType: String, persistence: Persistence)
      extends ShardingMessageExtractor[ShardingEnvelope[M], M] {

    override def entityId(envelope: ShardingEnvelope[M]): String = envelope.entityId

    override def shardId(entityId: String): String = {
      val persistenceId = PersistenceId.concat(entityType, entityId)
      val slice = persistence.sliceForPersistenceId(persistenceId)
      slice.toString
    }

    override def unwrapMessage(envelope: ShardingEnvelope[M]): M = envelope.message
  }
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
  private var numberOfSlices = 1024 // initialized for real in start

  private val shardSuitabilityOrdering =
    new ClusterShardAllocationMixin.ShardSuitabilityOrdering(preferLeastShards = false)

  override def start(system: ActorSystem): Unit = {
    cluster = Cluster(system)
    numberOfSlices = Persistence(system).numberOfSlices
  }

  override protected def clusterState: CurrentClusterState = cluster.state
  override protected def selfMember: Member = cluster.selfMember

  private var previousRebalance = Set.empty[ShardId]

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]]): Future[ActorRef] = {
    val slice = shardId.toInt
    if (slice >= numberOfSlices)
      throw new IllegalArgumentException("slice must be between 0 and 1023. Use `ShardBySliceMessageExtractor`.")

    val sortedRegionEntries = regionEntriesFor(currentShardAllocations).toVector.sorted(shardSuitabilityOrdering)

    findRegionWithNeighbor(slice, sortedRegionEntries) match {
      case Some(regionWithNeighbor) =>
        Future.successful(regionWithNeighbor)
      case None =>
        Future.successful(allocateWithoutNeighbor(sortedRegionEntries))
    }
  }

  private def allocateWithoutNeighbor(sortedRegionEntries: Vector[RegionEntry]): ActorRef = {
    val sortedByLeastShards = sortedRegionEntries.sorted(ClusterShardAllocationMixin.ShardSuitabilityOrdering)
    sortedByLeastShards.head.region
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
      val sortedRegionEntries = regionEntriesFor(currentShardAllocations).toVector.sorted(shardSuitabilityOrdering)
      if (!isAGoodTimeToRebalance(sortedRegionEntries)) {
        emptyRebalanceResult
      } else {
        val selected = Vector.newBuilder[ShardId]
        sortedRegionEntries.zipWithIndex.foreach {
          case (RegionEntry(_, _, shards), i) =>
            val targetSize = optimalNumberOfShards(i, sortedRegionEntries.size)
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
        if (previousRebalance.size >= numberOfSlices / 4)
          previousRebalance = Set.empty[ShardId] // start over
        Future.successful(limitedResult)
      }
    }

  }

  private def findRegionWithNeighbor(slice: Int, sortedRegionEntries: Vector[RegionEntry]): Option[ActorRef] = {
    val maxDelta = 10

    @tailrec def find(delta: Int): Option[ActorRef] = {
      if (delta == maxDelta)
        None
      else {
        findRegionWithNeighbor(slice, -delta, sortedRegionEntries) match {
          case found @ Some(_) => found
          case None =>
            findRegionWithNeighbor(slice, delta, sortedRegionEntries) match {
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
      sortedRegionEntries: Vector[RegionEntry]): Option[ActorRef] = {
    val neighborSlice = slice + diff
    if (neighborSlice < 0 || neighborSlice > numberOfSlices - 1)
      None
    else {
      val neighbor = (slice + diff).toString

      // rebalance will pick from lower/upper slices to make the distribution more optimal,
      // and therefore there must be room for some overfill, otherwise it would just allocate
      // without neighbor collocation when reaching full allocation
      val overfill = 1
      sortedRegionEntries.zipWithIndex.collectFirst {
        case (RegionEntry(region, _, shards), i)
            if shards.contains(neighbor) && shards.size < optimalNumberOfShards(i, sortedRegionEntries.size) + overfill =>
          region
      }
    }
  }

  private def optimalNumberOfShards(i: Int, numberOfRegions: Int): Int = {
    val rounding = if (numberOfSlices % numberOfRegions == 0) 0 else if (i % 2 == 0) 1 else 0
    numberOfSlices / numberOfRegions + rounding
  }

}
