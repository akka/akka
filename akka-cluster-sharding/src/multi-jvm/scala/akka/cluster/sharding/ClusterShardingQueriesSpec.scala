/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor.Props
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

object ClusterShardingQueriesSpec {
  import MultiNodeClusterShardingSpec.PingPongActor

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg @ PingPongActor.Ping(id) => (id.toString, msg)
  }

  val numberOfShards = 6

  val extractShardId: ShardRegion.ExtractShardId = {
    case PingPongActor.Ping(id) => (id % numberOfShards).toString
  }

  val shardTypeName = "DatatypeA"
}

object ClusterShardingQueriesSpecConfig
    extends MultiNodeClusterShardingConfig(additionalConfig = """
        akka.log-dead-letters-during-shutdown = off
        akka.cluster.sharding {
          shard-region-query-timeout = 0ms
          updating-state-timeout = 2s
          waiting-for-state-timeout = 2s
        }
        """) {

  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")

  nodeConfig(first, second, third)(ConfigFactory.parseString("""akka.cluster.roles=["shard"]"""))

}

class ClusterShardingQueriesSpecMultiJvmNode1 extends ClusterShardingQueriesSpec
class ClusterShardingQueriesSpecMultiJvmNode2 extends ClusterShardingQueriesSpec
class ClusterShardingQueriesSpecMultiJvmNode3 extends ClusterShardingQueriesSpec
class ClusterShardingQueriesSpecMultiJvmNode4 extends ClusterShardingQueriesSpec

abstract class ClusterShardingQueriesSpec
    extends MultiNodeClusterShardingSpec(ClusterShardingQueriesSpecConfig)
    with ScalaFutures {

  import ClusterShardingQueriesSpec._
  import ClusterShardingQueriesSpecConfig._
  import MultiNodeClusterShardingSpec.PingPongActor

  lazy val region = ClusterSharding(system).shardRegion(shardTypeName)

  "Querying cluster sharding" must {

    "join cluster, initialize sharding" in {
      awaitClusterUp(controller, first, second, third)

      runOn(controller) {
        startProxy(
          system,
          typeName = shardTypeName,
          role = Some("shard"),
          extractEntityId = extractEntityId,
          extractShardId = extractShardId)
      }

      runOn(first, second, third) {
        startSharding(
          system,
          typeName = shardTypeName,
          entityProps = Props(new PingPongActor),
          settings = settings.withRole("shard"),
          extractEntityId = extractEntityId,
          extractShardId = extractShardId)
      }

      enterBarrier("sharding started")
    }

    "trigger sharded actors" in {
      runOn(controller) {
        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            (0 to 20).foreach(n => region.tell(PingPongActor.Ping(n), pingProbe.ref))
            pingProbe.receiveWhile(messages = 20) {
              case PingPongActor.Pong => ()
            }
          }
        }
      }
      enterBarrier("sharded actors started")
    }

    "get ShardIds of shards that timed out per region" in {
      runOn(roles: _*) {
        val probe = TestProbe()
        val region = ClusterSharding(system).shardRegion(shardTypeName)
        region.tell(ShardRegion.GetClusterShardingStats(10.seconds), probe.ref)
        val regions = probe.expectMsgType[ShardRegion.ClusterShardingStats].regions
        regions.size shouldEqual 3
        val timeouts = numberOfShards / regions.size

        // 3 regions, 2 shards per region, all 2 shards/region were unresponsive
        // within shard-region-query-timeout = 0ms
        regions.values.forall { s =>
          s.stats.isEmpty && s.failed.size == timeouts
        } shouldBe true

        regions.values.map(_.failed.size).sum shouldEqual numberOfShards
        enterBarrier("received stats")
      }
      enterBarrier("done")
    }

  }

}
