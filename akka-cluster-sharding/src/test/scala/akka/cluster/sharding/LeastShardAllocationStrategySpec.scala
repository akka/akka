/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.Props
import akka.testkit.AkkaSpec

class LeastShardAllocationStrategySpec extends AkkaSpec {
  import ShardCoordinator._

  val regionA = system.actorOf(Props.empty, "regionA")
  val regionB = system.actorOf(Props.empty, "regionB")
  val regionC = system.actorOf(Props.empty, "regionC")

  val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 3, maxSimultaneousRebalance = 2)

  "LeastShardAllocationStrategy" must {
    "allocate to region with least number of shards" in {
      val allocations = Map(regionA → Vector("shard1"), regionB → Vector("shard2"), regionC → Vector.empty)
      allocationStrategy.allocateShard(regionA, "shard3", allocations).futureValue should ===(regionC)
    }

    "rebalance from region with most number of shards" in {
      val allocations = Map(regionA → Vector("shard1"), regionB → Vector("shard2", "shard3"),
        regionC → Vector.empty)

      // so far regionB has 2 shards and regionC has 0 shards, but the diff is less than rebalanceThreshold
      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set.empty[String])

      val allocations2 = allocations.updated(regionB, Vector("shard2", "shard3", "shard4"))
      allocationStrategy.rebalance(allocations2, Set.empty).futureValue should ===(Set("shard2", "shard3"))
      allocationStrategy.rebalance(allocations2, Set("shard4")).futureValue should ===(Set.empty[String])

      val allocations3 = allocations2.updated(regionA, Vector("shard1", "shard5", "shard6"))
      allocationStrategy.rebalance(allocations3, Set("shard1")).futureValue should ===(Set("shard2"))
    }

    "rebalance multiple shards if max simultaneous rebalances is not exceeded" in {
      val allocations = Map(
        regionA → Vector("shard1"),
        regionB → Vector("shard2", "shard3", "shard4", "shard5", "shard6"),
        regionC → Vector.empty)

      allocationStrategy.rebalance(allocations, Set.empty).futureValue should ===(Set("shard2", "shard3"))
      allocationStrategy.rebalance(allocations, Set("shard2", "shard3")).futureValue should ===(Set.empty[String])
    }

    "limit number of simultaneous rebalance" in {
      val allocations = Map(
        regionA → Vector("shard1"),
        regionB → Vector("shard2", "shard3", "shard4", "shard5", "shard6"),
        regionC → Vector.empty)

      allocationStrategy.rebalance(allocations, Set("shard2")).futureValue should ===(Set("shard3"))
      allocationStrategy.rebalance(allocations, Set("shard2", "shard3")).futureValue should ===(Set.empty[String])
    }

    "don't rebalance excessive shards if maxSimultaneousRebalance > rebalanceThreshold" in {
      val allocationStrategy =
        new LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 5)

      val allocations = Map(
        regionA → Vector("shard1", "shard2", "shard3", "shard4", "shard5", "shard6", "shard7", "shard8"),
        regionB → Vector("shard9", "shard10", "shard11", "shard12"))

      allocationStrategy.rebalance(allocations, Set("shard2")).futureValue should
        ===(Set("shard1", "shard3", "shard4"))
      allocationStrategy.rebalance(allocations, Set("shard5", "shard6", "shard7", "shard8")).futureValue should
        ===(Set.empty[String])
    }
  }
}
