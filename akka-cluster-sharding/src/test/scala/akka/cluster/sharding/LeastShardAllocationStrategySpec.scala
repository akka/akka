/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import scala.concurrent.Await
import scala.concurrent.duration._
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
      Await.result(allocationStrategy.allocateShard(regionA, "shard3", allocations), 3.seconds) should ===(regionC)
    }

    "rebalance from region with most number of shards" in {
      val allocations = Map(regionA → Vector("shard1"), regionB → Vector("shard2", "shard3"),
        regionC → Vector.empty)

      // so far regionB has 2 shards and regionC has 0 shards, but the diff is less than rebalanceThreshold
      Await.result(allocationStrategy.rebalance(allocations, Set.empty), 3.seconds) should ===(Set.empty[String])

      val allocations2 = allocations.updated(regionB, Vector("shard2", "shard3", "shard4"))
      Await.result(allocationStrategy.rebalance(allocations2, Set.empty), 3.seconds) should ===(Set("shard2"))
      Await.result(allocationStrategy.rebalance(allocations2, Set("shard4")), 3.seconds) should ===(Set.empty[String])

      val allocations3 = allocations2.updated(regionA, Vector("shard1", "shard5", "shard6"))
      Await.result(allocationStrategy.rebalance(allocations3, Set("shard1")), 3.seconds) should ===(Set("shard2"))
    }

    "must limit number of simultanious rebalance" in {
      val allocations = Map(
        regionA → Vector("shard1"),
        regionB → Vector("shard2", "shard3", "shard4", "shard5", "shard6"), regionC → Vector.empty)

      Await.result(allocationStrategy.rebalance(allocations, Set("shard2")), 3.seconds) should ===(Set("shard3"))
      Await.result(allocationStrategy.rebalance(allocations, Set("shard2", "shard3")), 3.seconds) should ===(Set.empty[String])
    }
  }
}
