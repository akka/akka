/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.ActorRef
import akka.actor.Props
import akka.testkit.AkkaSpec

class LeastShardAllocationStrategySpec extends AkkaSpec {
  import ShardCoordinator._

  val regionA = system.actorOf(Props.empty, "regionA")
  val regionB = system.actorOf(Props.empty, "regionB")
  val regionC = system.actorOf(Props.empty, "regionC")

  def createAllocations(aCount: Int, bCount: Int = 0, cCount: Int = 0): Map[ActorRef, Vector[String]] = {
    val shards = (1 to (aCount + bCount + cCount)).map(n => ("00" + n.toString).takeRight(3))
    Map(
      regionA -> shards.take(aCount).toVector,
      regionB -> shards.slice(aCount, aCount + bCount).toVector,
      regionC -> shards.takeRight(cCount).toVector)
  }

  "LeastShardAllocationStrategy" must {
    "allocate to region with least number of shards" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 3, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 1, bCount = 1)
      allocationStrategy.allocateShard(regionA, "003", allocations).futureValue should ===(regionC)
    }

    "rebalance from region with most number of shards [2, 0, 0], rebalanceThreshold=1" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 2)

      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001"))
      allocationStrategy.rebalance(allocations, Set("001")).futureValue should ===(Set.empty[String])
    }

    "not rebalance when diff equal to threshold, [1, 1, 0], rebalanceThreshold=1" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 1, bCount = 1)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])
    }

    "rebalance from region with most number of shards [1, 2, 0], rebalanceThreshold=1" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 1, bCount = 2)

      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("002"))
      allocationStrategy.rebalance(allocations, Set("002")).futureValue should ===(Set.empty[String])
    }

    "rebalance from region with most number of shards [3, 0, 0], rebalanceThreshold=1" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 3)

      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001"))
      allocationStrategy.rebalance(allocations, Set("001")).futureValue should ===(Set("002"))
    }

    "rebalance from region with most number of shards [4, 4, 0], rebalanceThreshold=1" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 4, bCount = 4)

      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001"))
      allocationStrategy.rebalance(allocations, Set("001")).futureValue should ===(Set("005"))
    }

    "rebalance from region with most number of shards [4, 4, 2], rebalanceThreshold=1" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 1, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 4, bCount = 4, cCount = 2)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001"))
      // not optimal, 005 stopped and started again, but ok
      allocationStrategy.rebalance(allocations, Set("001")).futureValue should ===(Set("005"))
    }

    "rebalance from region with most number of shards [1, 3, 0], rebalanceThreshold=2" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 1, bCount = 2)

      // so far regionB has 2 shards and regionC has 0 shards, but the diff is <= rebalanceThreshold
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])

      val allocations2 = createAllocations(aCount = 1, bCount = 3)
      allocationStrategy.rebalance(allocations2, Set.empty).futureValue should ===(Set("002"))
      allocationStrategy.rebalance(allocations2, Set("002")).futureValue should ===(Set.empty[String])
    }

    "not rebalance when diff equal to threshold, [2, 2, 0], rebalanceThreshold=2" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 2, bCount = 2)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])
    }

    "rebalance from region with most number of shards [3, 3, 0], rebalanceThreshold=2" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 3, bCount = 3)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001"))
      allocationStrategy.rebalance(allocations, Set("001")).futureValue should ===(Set("004"))
      allocationStrategy.rebalance(allocations, Set("001", "004")).futureValue should ===(Set.empty)
    }

    "rebalance from region with most number of shards [4, 4, 0], rebalanceThreshold=2" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 4, bCount = 4)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001", "002"))
      allocationStrategy.rebalance(allocations, Set("001", "002")).futureValue should ===(Set("005", "006"))
      allocationStrategy.rebalance(allocations, Set("001", "002", "005", "006")).futureValue should ===(Set.empty)
    }

    "rebalance from region with most number of shards [5, 5, 0], rebalanceThreshold=2" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 10)
      val allocations = createAllocations(aCount = 5, bCount = 5)
      // optimal would => [4, 4, 2] or even => [3, 4, 3]
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001", "002"))
      // if 001 and 002 are not started quickly enough this is stopping more than optimal
      allocationStrategy.rebalance(allocations, Set("001", "002")).futureValue should ===(Set("006", "007"))
      allocationStrategy.rebalance(allocations, Set("001", "002", "006", "007")).futureValue should ===(Set("003"))
    }

    "rebalance from region with most number of shards [50, 50, 0], rebalanceThreshold=2" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 100)
      val allocations = createAllocations(aCount = 50, cCount = 50)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("001", "002"))
      allocationStrategy.rebalance(allocations, Set("001", "002")).futureValue should ===(Set("051", "052"))
      allocationStrategy.rebalance(allocations, Set("001", "002", "051", "052")).futureValue should ===(
        Set("003", "004"))
    }

    "limit number of simultaneous rebalance" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 3, maxSimultaneousRebalance = 2)
      val allocations = createAllocations(aCount = 1, bCount = 10)
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("002", "003"))
      allocationStrategy.rebalance(allocations, Set("002", "003")).futureValue should ===(Set.empty[String])
    }

    "not pick shards that are in progress" in {
      val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 3, maxSimultaneousRebalance = 4)
      val allocations = createAllocations(aCount = 10)
      allocationStrategy.rebalance(allocations, Set("002", "003")).futureValue should ===(Set("001", "004"))
    }

  }
}
