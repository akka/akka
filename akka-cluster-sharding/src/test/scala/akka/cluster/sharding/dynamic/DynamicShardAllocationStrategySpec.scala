/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic

import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategy.GetShardLocation
import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategy.GetShardLocationResponse
import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategy.GetShardLocations
import akka.testkit.AkkaSpec
import akka.testkit.TestProbe
import akka.util.Timeout

import scala.concurrent.duration._

class DynamicShardAllocationStrategySpec extends AkkaSpec("""
    akka.actor.provider = cluster 
    akka.loglevel = INFO 
    """) {

  val requester = TestProbe()

  "DynamicShardAllocation allocate" must {
    "default to requester if query times out" in {
      val (strat, _) = createStrategy()
      strat.allocateShard(requester.ref, "shard-1", Map.empty).futureValue shouldEqual requester.ref
    }
    "default to requester if no allocation" in {
      val (strat, probe) = createStrategy()
      val allocation = strat.allocateShard(requester.ref, "shard-1", Map.empty)
      probe.expectMsg(GetShardLocation("shard-1"))
      probe.reply(GetShardLocationResponse(None))
      allocation.futureValue shouldEqual requester.ref
    }
  }

  "DynamicShardAllocation rebalance" must {
    "default to no rebalance if query times out" in {
      val (strat, probe) = createStrategy()
      val rebalance = strat.rebalance(Map.empty, Set.empty)
      probe.expectMsg(GetShardLocations)
      rebalance.futureValue shouldEqual Set.empty
    }
  }

  def createStrategy(): (DynamicShardAllocationStrategy, TestProbe) = {
    val probe = TestProbe()
    val strategy = new DynamicShardAllocationStrategy(system, "type")(Timeout(250.millis)) {
      override private[akka] def createShardStateActor() = probe.ref
    }
    strategy.start()
    (strategy, probe)
  }

}
