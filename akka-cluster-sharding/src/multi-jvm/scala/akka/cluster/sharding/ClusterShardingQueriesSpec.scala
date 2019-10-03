/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.MultiNodeClusterSpec
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.serialization.jackson.CborSerializable
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

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
  val first = role("first")
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
      shard-region-query-timeout = 0ms
      updating-state-timeout = 2s
      waiting-for-state-timeout = 2s
    }
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ClusterShardingGetStatsSpec/sharding-ddata
      map-size = 10 MiB
    }
    """).withFallback(MultiNodeClusterSpec.clusterConfig)))

  nodeConfig(first, second, third)(ConfigFactory.parseString("""akka.cluster.roles=["shard"]"""))

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
      awaitClusterUp(controller, first, second, third)

      runOn(controller) {
        startProxy()
      }

      runOn(first, second, third) {
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
