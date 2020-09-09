/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.ActorRef
import akka.actor.Props
import akka.testkit.AkkaSpec

class LeastShardAllocationStrategySpec extends AkkaSpec {

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
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("002"))
    }

    "rebalance shards [2, 0, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 2)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001"))
    }

    "not rebalance shards [1, 1, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 1, bCount = 1)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])
    }

    "rebalance shards [3, 0, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 3)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001", "002"))
    }

    "rebalance shards [4, 4, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 4, bCount = 4)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001", "005"))
    }

    "rebalance shards [4, 4, 2]" in {
      // this is handled by phase 2, to find diff of 2
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 4, bCount = 4, cCount = 2)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001"))
    }

    "rebalance shards [5, 5, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 5, bCount = 5)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001", "006"))

      // so far [4, 4, 2]
      val allocations2 = createAllocations(aCount = 4, bCount = 4, cCount = 2)
      // second phase will find the diff of 2, resulting in [3, 4, 3]
      allocationStrategy.rebalance(allocations2, Set.empty).futureValue should ===(Set("001"))
    }

    "rebalance shards [50, 50, 0]" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 50, cCount = 50)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(
        shards.take(50 - 34).toSet ++ shards.drop(50).take(50 - 34))

      // so far [34, 34, 32]
      val allocations2 = createAllocations(aCount = 34, bCount = 34, cCount = 32)
      // second phase will find the diff of 2, resulting in [33, 34, 33]
      allocationStrategy.rebalance(allocations2, Set.empty).futureValue should ===(Set("001"))
    }

    "respect absolute limit of number shards" in {
      val allocationStrategy = new LeastShardAllocationStrategy(absoluteLimit = 3, relativeLimit = 1.0)
      val allocations = createAllocations(aCount = 1, bCount = 9)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("002", "003", "004"))
    }

    "respect relative limit of number shards" in {
      val allocationStrategy = new LeastShardAllocationStrategy(absoluteLimit = 5, relativeLimit = 0.3)
      val allocations = createAllocations(aCount = 1, bCount = 9)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("002", "003", "004"))
    }

    "not rebalance when in progress" in {
      val allocationStrategy = strategyWithoutLimits
      val allocations = createAllocations(aCount = 10)
      allocationStrategy.rebalance(allocations, Set("002", "003")).futureValue should ===(Set.empty[String])
    }

  }
}
