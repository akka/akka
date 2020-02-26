/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.cluster.MultiNodeClusterSpec
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.serialization.jackson.CborSerializable
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

object ClusterShardingQueriesSpec {
  case class Ping(id: Long) extends CborSerializable
  case object Pong extends CborSerializable

  class EntityActor extends Actor with ActorLogging {
    def receive: Receive = {
      case _: Ping => sender() ! Pong
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg @ Ping(id) => (id.toString, msg)
  }

  val numberOfShards = 6

  val extractShardId: ShardRegion.ExtractShardId = {
    case Ping(id) => (id % numberOfShards).toString
  }

  val shardTypeName = "DatatypeA"
}

object ClusterShardingQueriesSpecConfig extends MultiNodeConfig {

  val controller = role("controller")
  val busy = role("busy")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.classic.log-remote-lifecycle-events = off
    akka.log-dead-letters-during-shutdown = off
    akka.cluster.downing-provider-class = akka.cluster.testkit.AutoDowning
    akka.cluster.testkit.auto-down-unreachable-after = 0s
    akka.cluster.sharding {
      state-store-mode = "ddata"
      shard-region-query-timeout = 2ms
      updating-state-timeout = 2s
      waiting-for-state-timeout = 2s
    }
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ClusterShardingGetStatsSpec/sharding-ddata
      map-size = 10 MiB
    }
    """).withFallback(MultiNodeClusterSpec.clusterConfig)))

  val shardRoles = ConfigFactory.parseString("""akka.cluster.roles=["shard"]""")

  nodeConfig(busy)(
    ConfigFactory.parseString("akka.cluster.sharding.shard-region-query-timeout = 0ms").withFallback(shardRoles))
  nodeConfig(second, third)(shardRoles)

}

class ClusterShardingQueriesSpecMultiJvmNode1 extends ClusterShardingQueriesSpec
class ClusterShardingQueriesSpecMultiJvmNode2 extends ClusterShardingQueriesSpec
class ClusterShardingQueriesSpecMultiJvmNode3 extends ClusterShardingQueriesSpec
class ClusterShardingQueriesSpecMultiJvmNode4 extends ClusterShardingQueriesSpec

abstract class ClusterShardingQueriesSpec
    extends MultiNodeSpec(ClusterShardingQueriesSpecConfig)
    with MultiNodeClusterSpec
    with ScalaFutures {

  import ClusterShardingQueriesSpec._
  import ClusterShardingQueriesSpecConfig._

  def startShard(): ActorRef = {
    ClusterSharding(system).start(
      typeName = shardTypeName,
      entityProps = Props(new EntityActor),
      settings = ClusterShardingSettings(system).withRole("shard"),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  def startProxy(): ActorRef = {
    ClusterSharding(system).startProxy(
      typeName = shardTypeName,
      role = Some("shard"),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion(shardTypeName)

  "Querying cluster sharding" must {

    "join cluster, initialize sharding" in {
      awaitClusterUp(controller, busy, second, third)

      runOn(controller) {
        startProxy()
      }

      runOn(busy, second, third) {
        startShard()
      }

      enterBarrier("sharding started")
    }

    "trigger sharded actors" in {
      runOn(controller) {
        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            (0 to 20).foreach(n => region.tell(Ping(n), pingProbe.ref))
            pingProbe.receiveWhile(messages = 20) {
              case Pong => ()
            }
          }
        }
      }
      enterBarrier("sharded actors started")
    }

    "return shard stats of cluster sharding regions if one or more shards timeout, versus all as empty" in {
      runOn(busy, second, third) {
        val probe = TestProbe()
        val region = ClusterSharding(system).shardRegion(shardTypeName)
        region.tell(ShardRegion.GetClusterShardingStats(10.seconds), probe.ref)
        val regions = probe.expectMsgType[ShardRegion.ClusterShardingStats].regions
        regions.size shouldEqual 3
        val timeouts = numberOfShards / regions.size

        // 3 regions, 2 shards per region, all 2 shards/region were unresponsive
        // within shard-region-query-timeout, which only on first is 0ms
        regions.values.map(_.stats.size).sum shouldEqual 4
        regions.values.map(_.failed.size).sum shouldEqual timeouts
      }
      enterBarrier("received failed stats from timed out shards vs empty")
    }

    "return shard state of sharding regions if one or more shards timeout, versus all as empty" in {
      runOn(busy) {
        val probe = TestProbe()
        val region = ClusterSharding(system).shardRegion(shardTypeName)
        region.tell(ShardRegion.GetShardRegionState, probe.ref)
        val state = probe.expectMsgType[ShardRegion.CurrentShardRegionState]
        state.shards.isEmpty shouldEqual true
        state.failed.size shouldEqual 2
      }
      enterBarrier("query-timeout-on-busy-node")

      runOn(second, third) {
        val probe = TestProbe()
        val region = ClusterSharding(system).shardRegion(shardTypeName)

        region.tell(ShardRegion.GetShardRegionState, probe.ref)
        val state = probe.expectMsgType[ShardRegion.CurrentShardRegionState]
        state.shards.size shouldEqual 2
        state.failed.isEmpty shouldEqual true
      }
      enterBarrier("done")
    }

  }

}
