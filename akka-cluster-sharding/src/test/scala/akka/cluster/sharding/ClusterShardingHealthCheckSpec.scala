/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.testkit.AkkaSpec
import akka.testkit.TestProbe
import akka.testkit.WithLogCapturing
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

object ClusterShardingHealthCheckSpec {
  val config = ConfigFactory.parseString("""
          akka.loglevel = DEBUG
          akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
            """.stripMargin)
}

class ClusterShardingHealthCheckSpec
    extends AkkaSpec(ClusterShardingHealthCheckSpec.config)
    with WithLogCapturing
    with ScalaFutures {

  "Sharding health check" should {
    "pass if no checks configured" in {
      val shardRegionProbe = TestProbe()
      val check = new ClusterShardingHealthCheck(
        system,
        new ClusterShardingHealthCheckSettings(Set.empty, 1.second),
        _ => shardRegionProbe.ref)
      check().futureValue shouldEqual true
    }
    "pass if all region return true" in {
      val shardRegionProbe = TestProbe()
      val check = new ClusterShardingHealthCheck(
        system,
        new ClusterShardingHealthCheckSettings(Set("cat"), 1.second),
        _ => shardRegionProbe.ref)
      val response = check()
      shardRegionProbe.expectMsg(ShardRegion.GetShardRegionStatus)
      shardRegionProbe.reply(new ShardRegion.ShardRegionStatus("cat", true))
      response.futureValue shouldEqual true
    }
    "fail if all region returns false" in {
      val shardRegionProbe = TestProbe()
      val check = new ClusterShardingHealthCheck(
        system,
        new ClusterShardingHealthCheckSettings(Set("cat"), 1.second),
        _ => shardRegionProbe.ref)
      val response = check()
      shardRegionProbe.expectMsg(ShardRegion.GetShardRegionStatus)
      shardRegionProbe.reply(new ShardRegion.ShardRegionStatus("cat", false))
      response.futureValue shouldEqual false
    }
    "fail if a subset region returns false" in {
      val shardRegionProbe = TestProbe()
      val check = new ClusterShardingHealthCheck(
        system,
        new ClusterShardingHealthCheckSettings(Set("cat", "dog"), 1.second),
        _ => shardRegionProbe.ref)
      val response = check()
      shardRegionProbe.expectMsg(ShardRegion.GetShardRegionStatus)
      shardRegionProbe.reply(new ShardRegion.ShardRegionStatus("cat", true))
      shardRegionProbe.expectMsg(ShardRegion.GetShardRegionStatus)
      shardRegionProbe.reply(new ShardRegion.ShardRegionStatus("dog", false))
      response.futureValue shouldEqual false
    }
    "times out" in {
      val shardRegionProbe = TestProbe()
      val check = new ClusterShardingHealthCheck(
        system,
        new ClusterShardingHealthCheckSettings(Set("cat"), 100.millis),
        _ => shardRegionProbe.ref)
      val response = check()
      shardRegionProbe.expectMsg(ShardRegion.GetShardRegionStatus)
      // don't reply
      response.futureValue shouldEqual false
    }
    "always pass after all regions have reported registered" in {
      val shardRegionProbe = TestProbe()
      val check = new ClusterShardingHealthCheck(
        system,
        new ClusterShardingHealthCheckSettings(Set("cat"), 1.second),
        _ => shardRegionProbe.ref)
      val response = check()
      shardRegionProbe.expectMsg(ShardRegion.GetShardRegionStatus)
      shardRegionProbe.reply(new ShardRegion.ShardRegionStatus("cat", true))
      response.futureValue shouldEqual true

      val secondResponse = check()
      shardRegionProbe.expectNoMessage()
      secondResponse.futureValue shouldEqual true
    }
  }

}
