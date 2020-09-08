/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.collection.immutable
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId

/**
 * INTERNAL API
 */
@InternalApi private[akka] object LeastShardAllocationStrategy2 {
  private val emptyRebalanceResult = Future.successful(Set.empty[ShardId])

  val ConfigValue = "least-shard-allocation-strategy2"
}

final class LeastShardAllocationStrategy2(absoluteLimit: Int, relativeLimit: Double) extends ShardAllocationStrategy {
  import LeastShardAllocationStrategy2.emptyRebalanceResult

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
    s"LeastShardAllocationStrategy2($absoluteLimit,$relativeLimit)"
}
