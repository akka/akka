/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import akka.testkit.AkkaSpec
import akka.actor.Props

class LeastShardAllocationStrategySpec extends AkkaSpec {
  import ShardCoordinator._

  val regionA = system.actorOf(Props.empty, "regionA")
  val regionB = system.actorOf(Props.empty, "regionB")
  val regionC = system.actorOf(Props.empty, "regionC")

  val allocationStrategy = new LeastShardAllocationStrategy(rebalanceThreshold = 3, maxSimultaneousRebalance = 2)

  "LeastShardAllocationStrategy" must {
    "allocate to region with least number of shards" in {
      val allocations = Map(regionA -> Vector("shard1"), regionB -> Vector("shard2"), regionC -> Vector.empty)
      allocationStrategy.allocateShard(regionA, "shard3", allocations) should be(regionC)
    }

    "rebalance from region with most number of shards" in {
      val allocations = Map(regionA -> Vector("shard1"), regionB -> Vector("shard2", "shard3"),
        regionC -> Vector.empty)

      // so far regionB has 2 shards and regionC has 0 shards, but the diff is less than rebalanceThreshold  
      allocationStrategy.rebalance(allocations, Set.empty) should be(Set.empty)

      val allocations2 = allocations.updated(regionB, Vector("shard2", "shard3", "shard4"))
      allocationStrategy.rebalance(allocations2, Set.empty) should be(Set("shard2"))
      allocationStrategy.rebalance(allocations2, Set("shard4")) should be(Set.empty)

      val allocations3 = allocations2.updated(regionA, Vector("shard1", "shard5", "shard6"))
      allocationStrategy.rebalance(allocations3, Set("shard1")) should be(Set("shard2"))
    }

    "must limit number of simultanious rebalance" in {
      val allocations = Map(regionA -> Vector("shard1"),
        regionB -> Vector("shard2", "shard3", "shard4", "shard5", "shard6"), regionC -> Vector.empty)

      allocationStrategy.rebalance(allocations, Set("shard2")) should be(Set("shard3"))
      allocationStrategy.rebalance(allocations, Set("shard2", "shard3")) should be(Set.empty)
    }
  }
}