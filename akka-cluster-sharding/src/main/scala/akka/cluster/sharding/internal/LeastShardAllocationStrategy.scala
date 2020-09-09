/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import scala.collection.immutable
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId

/**
 * INTERNAL API
 */
@InternalApi private[akka] object LeastShardAllocationStrategy {
  private val emptyRebalanceResult = Future.successful(Set.empty[ShardId])
}

/**
 * INTERNAL API: Use `ShardCoordinator.ShardAllocationStrategy.leastShardAllocationStrategy` factory method.
 *
 * `ShardAllocationStrategy` that  allocates new shards to the `ShardRegion` (node) with least
 * number of previously allocated shards.
 *
 * When a node is added to the cluster the shards on the existing nodes will be rebalanced to the new node.
 * The `LeastShardAllocationStrategy` picks shards for rebalancing from the `ShardRegion`s with most number
 * of previously allocated shards. They will then be allocated to the `ShardRegion` with least number of
 * previously allocated shards, i.e. new members in the cluster. The amount of shards to rebalance in each
 * round can be limited to make it progress slower since rebalancing too many shards at the same time could
 * result in additional load on the system. For example, causing many Event Sourced entites to be started
 * at the same time.
 *
 * It will not rebalance when there is already an ongoing rebalance in progress.
 *
 * @param absoluteLimit the maximum number of shards that will be rebalanced in one rebalance round
 * @param relativeLimit fraction (< 1.0) of total number of (known) shards that will be rebalanced
 *                      in one rebalance round
 */
@InternalApi private[akka] final class LeastShardAllocationStrategy(absoluteLimit: Int, relativeLimit: Double)
    extends ShardAllocationStrategy {
  import LeastShardAllocationStrategy.emptyRebalanceResult

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef] = {
    val (regionWithLeastShards, _) = currentShardAllocations.minBy { case (_, v) => v.size }
    Future.successful(regionWithLeastShards)
  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {
    import math.max
    import math.min

    def limit(numberOfShards: Int): Int =
      max(1, min((relativeLimit * numberOfShards).toInt, absoluteLimit))

    def rebalancePhase1(
        numberOfShards: Int,
        optimalPerRegion: Int,
        sortedAllocations: Vector[immutable.IndexedSeq[ShardId]]): Set[ShardId] = {
      val selected = Vector.newBuilder[ShardId]
      sortedAllocations.foreach { shards =>
        if (shards.size > optimalPerRegion) {
          selected ++= shards.take(shards.size - optimalPerRegion)
        }
      }
      val result = selected.result()
      result.take(limit(numberOfShards)).toSet
    }

    def rebalancePhase2(
        numberOfShards: Int,
        optimalPerRegion: Int,
        sortedAllocations: Vector[immutable.IndexedSeq[ShardId]]): Future[Set[ShardId]] = {
      // In the first phase the optimalPerRegion is rounded up, and depending on number of shards per region and number
      // of regions that might not be the exact optimal.
      // In second phase we look for diff of >= 2 below optimalPerRegion and rebalance that number of shards.
      val countBelowOptimal =
        sortedAllocations.iterator.map(shards => max(0, (optimalPerRegion - 1) - shards.size)).sum
      if (countBelowOptimal == 0) {
        emptyRebalanceResult
      } else {
        val selected = Vector.newBuilder[ShardId]
        sortedAllocations.foreach { shards =>
          if (shards.size >= optimalPerRegion) {
            selected += shards.head
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
      val numberOfShards = currentShardAllocations.valuesIterator.map(_.size).sum
      val numberOfRegions = currentShardAllocations.size
      if (numberOfRegions == 0 || numberOfShards == 0) {
        emptyRebalanceResult
      } else {
        val sortedAllocations = currentShardAllocations.valuesIterator.toVector.sortBy(_.size)
        val optimalPerRegion = numberOfShards / numberOfRegions + (if (numberOfShards % numberOfRegions == 0) 0 else 1)

        val result1 = rebalancePhase1(numberOfShards, optimalPerRegion, sortedAllocations)

        if (result1.nonEmpty) {
          Future.successful(result1)
        } else {
          rebalancePhase2(numberOfShards, optimalPerRegion, sortedAllocations)
        }
      }
    }
  }

  override def toString: ShardId =
    s"LeastShardAllocationStrategy($absoluteLimit,$relativeLimit)"
}
