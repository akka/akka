/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.collection.immutable

import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.actor.ActorRefProvider
import akka.actor.Address
import akka.actor.MinimalActorRef
import akka.actor.Props
import akka.actor.RootActorPath
import akka.cluster.sharding.ShardRegion.ShardId
import akka.testkit.AkkaSpec

object LeastShardAllocationStrategySpec {

  private object DummyActorRef extends MinimalActorRef {
    override val path: ActorPath = RootActorPath(Address("akka", "myapp")) / "system" / "fake"

    override def provider: ActorRefProvider = ???
  }

  def afterRebalance(
      allocationStrategy: LeastShardAllocationStrategy,
      allocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalance: Set[ShardId]): Map[ActorRef, immutable.IndexedSeq[ShardId]] = {
    val allocationsAfterRemoval = allocations.map {
      case (region, shards) => region -> shards.filterNot(rebalance)
    }

    rebalance.toList.sorted.foldLeft(allocationsAfterRemoval) {
      case (acc, shard) =>
        val region = allocationStrategy.allocateShard(DummyActorRef, shard, acc).value.get.get
        acc.updated(region, acc(region) :+ shard)
    }
  }

  def countShardsPerRegion(newAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Vector[Int] = {
    newAllocations.valuesIterator.map(_.size).toVector
  }

  def countShards(allocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Int = {
    countShardsPerRegion(allocations).sum
  }

  def allocationCountsAfterRebalance(
      allocationStrategy: LeastShardAllocationStrategy,
      allocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalance: Set[ShardId]): Vector[Int] = {
    countShardsPerRegion(afterRebalance(allocationStrategy, allocations, rebalance))
  }
}

class LeastShardAllocationStrategySpec extends AkkaSpec {
  import LeastShardAllocationStrategySpec.{ afterRebalance, allocationCountsAfterRebalance }

  private val regionA = system.actorOf(Props.empty, "regionA")
  private val regionB = system.actorOf(Props.empty, "regionB")
  private val regionC = system.actorOf(Props.empty, "regionC")

  private val shards = (1 to 999).map(n => ("00" + n.toString).takeRight(3))

  def createAllocations(aCount: Int, bCount: Int = 0, cCount: Int = 0): Map[ActorRef, Vector[String]] = {
    Map(
      regionA -> shards.take(aCount).toVector,
      regionB -> shards.slice(aCount, aCount + bCount).toVector,
      regionC -> shards.slice(aCount + bCount, aCount + bCount + cCount).toVector)
  }

  private val strategyWithoutLimits = new LeastShardAllocationStrategy(absoluteLimit = 1000, relativeLimit = 1.0)

  "LeastShardAllocationStrategy" must {
    "allocate to region with least number of shards" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 1, bCount = 1)
      allocationStrategy.allocateShard(regionA, "003", allocations).futureValue should ===(regionC)
    }

    "rebalance shards [1, 2, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 1, bCount = 2)
      val result = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("002"))
      allocationCountsAfterRebalance(allocationStrategy, allocations, result) should ===(Vector(1, 1, 1))
    }

    "rebalance shards [2, 0, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 2)
      val result = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(allocationStrategy, allocations, result) should ===(Vector(1, 1, 0))
    }

    "not rebalance shards [1, 1, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 1, bCount = 1)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])
    }

    "rebalance shards [3, 0, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 3)
      val result = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "002"))
      allocationCountsAfterRebalance(allocationStrategy, allocations, result) should ===(Vector(1, 1, 1))
    }

    "rebalance shards [4, 4, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 4, bCount = 4)
      val result = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001", "005"))
      allocationCountsAfterRebalance(allocationStrategy, allocations, result) should ===(Vector(3, 3, 2))
    }

    "rebalance shards [4, 4, 2]" in {
      // this is handled by phase 2, to find diff of 2
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 4, bCount = 4, cCount = 2)
      val result = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("001"))
      allocationCountsAfterRebalance(allocationStrategy, allocations, result) should ===(Vector(3, 4, 3))
    }

    "rebalance shards [5, 5, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 5, bCount = 5)
      val result1 = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result1 should ===(Set("001", "006"))

      // so far [4, 4, 2]
      allocationCountsAfterRebalance(allocationStrategy, allocations, result1) should ===(Vector(4, 4, 2))
      val allocations2 = afterRebalance(allocationStrategy, allocations, result1)
      // second phase will find the diff of 2, resulting in [3, 4, 3]
      val result2 = allocationStrategy.rebalance(allocations2, Set.empty).futureValue
      result2 should ===(Set("002"))
      allocationCountsAfterRebalance(allocationStrategy, allocations2, result2) should ===(Vector(3, 4, 3))
    }

    "rebalance shards [50, 50, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 50, cCount = 50)
      val result1 = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result1 should ===(shards.take(50 - 34).toSet ++ shards.drop(50).take(50 - 34))

      // so far [34, 34, 32]
      allocationCountsAfterRebalance(allocationStrategy, allocations, result1).sorted should ===(
        Vector(34, 34, 32).sorted)
      val allocations2 = createAllocations(aCount = 34, bCount = 34, cCount = 32)
      // second phase will find the diff of 2, resulting in [33, 34, 33]
      val result2 = allocationStrategy.rebalance(allocations2, Set.empty).futureValue
      result2 should ===(Set("001"))
      allocationCountsAfterRebalance(allocationStrategy, allocations2, result2).sorted should ===(
        Vector(33, 34, 33).sorted)
    }

    "respect absolute limit of number shards" in {
      val allocationStrategy = new LeastShardAllocationStrategy(absoluteLimit = 3, relativeLimit = 1.0)
      val allocations = createAllocations(aCount = 1, bCount = 9)
      val result = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("002", "003", "004"))
      allocationCountsAfterRebalance(allocationStrategy, allocations, result) should ===(Vector(2, 6, 2))
    }

    "respect relative limit of number shards" in {
      val allocationStrategy = new LeastShardAllocationStrategy(absoluteLimit = 5, relativeLimit = 0.3)
      val allocations = createAllocations(aCount = 1, bCount = 9)
      val result = allocationStrategy.rebalance(allocations, Set.empty).futureValue
      result should ===(Set("002", "003", "004"))
      allocationCountsAfterRebalance(allocationStrategy, allocations, result) should ===(Vector(2, 6, 2))
    }

    "not rebalance when in progress" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 10)
      allocationStrategy.rebalance(allocations, Set("002", "003")).futureValue should ===(Set.empty[String])
    }

  }
}
