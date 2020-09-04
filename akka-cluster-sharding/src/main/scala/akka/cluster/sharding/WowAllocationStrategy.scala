/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.collection.immutable
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId

object WowAllocationStrategy {
  private val emptyRebalanceResult = Future.successful(Set.empty[ShardId])
}

// FIXME name
class WowAllocationStrategy(absoluteLimit: Int, relativeLimit: Double) extends ShardAllocationStrategy {
  import WowAllocationStrategy.emptyRebalanceResult

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
    import math.{ max, min }

    if (rebalanceInProgress.nonEmpty) {
      // one rebalance at a time
      emptyRebalanceResult
    } else {
      val numberOfShards = currentShardAllocations.valuesIterator.map(_.size).sum
      val numberOfRegions = currentShardAllocations.size
      val optimalPerRegion = numberOfShards / numberOfRegions + (if (numberOfShards % numberOfRegions == 0) 0 else 1)

      val selected = Vector.newBuilder[ShardId]
      currentShardAllocations.valuesIterator.toVector.sortBy(_.size).foreach { shards =>
        if (shards.size > optimalPerRegion) {
          selected ++= shards.take(shards.size - optimalPerRegion)
        }
      }
      val result = selected.result()
      val limit = max(1, min(min((relativeLimit * numberOfShards).toInt, result.size), min(absoluteLimit, result.size)))
      val limitedResult = result.take(limit)

      println(
        s"# rebalance, currentShardAllocations [${currentShardAllocations.valuesIterator.map(_.size).mkString(",")}], " +
        s"numberOfShards $numberOfShards, numberOfRegions, $numberOfRegions, optimalPerRegion $optimalPerRegion, " +
        s"currentShards [${currentShardAllocations.valuesIterator.map(_.sorted.mkString("(", ", ", ")")).mkString(" ")}] " +
        s"limit $limit, result [${limitedResult.toList.sorted.mkString(",")}]") // FIXME
      Future.successful(limitedResult.toSet)
    }
  }
}
