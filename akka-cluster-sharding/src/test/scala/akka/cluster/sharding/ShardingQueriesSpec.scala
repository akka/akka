/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.cluster.sharding.Shard.ShardStats
import akka.cluster.sharding.ShardRegion.ShardState
import akka.cluster.sharding.ShardingQueries.ShardsQueryResult
import akka.testkit.AkkaSpec

class ShardingQueriesSpec extends AkkaSpec {

  private val shards = Seq("a", "b", "busy")
  private val timeouts = Set("busy")

  "ShardsQueryResult" must {

    "reflect nothing to acquire metadata from - 0 shards" in {
      val qr = ShardsQueryResult[ShardState](Seq.empty, 0)
      qr.total shouldEqual qr.queried
      qr.isTotalFailed shouldBe false // you'd have to make > 0 attempts in order to fail
      qr.isAllSubsetFailed shouldBe false // same
    }

    "partition failures and responses by type and by convention (failed Left T Right)" in {
      val responses = Seq(ShardStats("a", 1), ShardStats("b", 1))
      val results = responses.map(Right(_)) ++ timeouts.map(Left(_))
      val qr = ShardsQueryResult[ShardStats](results, shards.size)
      qr.failed shouldEqual timeouts
      qr.responses shouldEqual responses
      qr.isTotalFailed shouldBe false
      qr.isAllSubsetFailed shouldBe false
    }

    "detect a subset query - not all queried" in {
      val responses = Seq(ShardStats("a", 1), ShardStats("b", 1))
      val results = responses.map(Right(_)) ++ timeouts.map(Left(_))
      val qr = ShardsQueryResult[ShardStats](results, shards.size + 1)
      qr.isAllSubsetFailed shouldBe false // is subset, not all failed
      qr.total > qr.queried shouldBe true
      qr.queried < shards.size
    }

    "partition when all failed" in {
      val results = Seq(Left("c"), Left("d"))
      val qr = ShardsQueryResult[ShardState](results, results.size)
      qr.total shouldEqual qr.queried
      qr.isTotalFailed shouldBe true
      qr.isAllSubsetFailed shouldBe false // not a subset
    }
  }

}
