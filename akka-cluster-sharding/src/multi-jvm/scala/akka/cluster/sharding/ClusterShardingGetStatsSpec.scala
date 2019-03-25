/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor._
import akka.cluster.{ Cluster, MemberStatus, MultiNodeClusterSpec }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit.{ TestDuration, TestProbe }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object ClusterShardingGetStatsSpec {
  case object Stop
  case class Ping(id: Long)
  case object Pong

  class ShardedActor extends Actor with ActorLogging {
    log.info(s"entity started {}", self.path)
    def receive = {
      case Stop    => context.stop(self)
      case _: Ping => sender() ! Pong
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg @ Ping(id) => (id.toString, msg)
  }

  val numberOfShards = 3

  val extractShardId: ShardRegion.ExtractShardId = {
    case Ping(id) => (id % numberOfShards).toString
  }

  val shardTypeName = "Ping"
}

object ClusterShardingGetStatsSpecConfig extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.log-dead-letters-during-shutdown = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.sharding {
      state-store-mode = "ddata"
      updating-state-timeout = 2s
      waiting-for-state-timeout = 2s
    }
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ClusterShardingGetStatsSpec/sharding-ddata
      map-size = 10 MiB
    }
    akka.actor.warn-about-java-serializer-usage=false
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(first, second, third)(ConfigFactory.parseString("""akka.cluster.roles=["shard"]"""))

}

class ClusterShardingGetStatsSpecMultiJvmNode1 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode2 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode3 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode4 extends ClusterShardingGetStatsSpec

abstract class ClusterShardingGetStatsSpec
    extends MultiNodeSpec(ClusterShardingGetStatsSpecConfig)
    with STMultiNodeSpec {

  import ClusterShardingGetStatsSpec._
  import ClusterShardingGetStatsSpecConfig._

  def initialParticipants = roles.size

  def startShard(): ActorRef = {
    ClusterSharding(system).start(
      typeName = shardTypeName,
      entityProps = Props(new ShardedActor),
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

  def join(from: RoleName): Unit = {
    runOn(from) {
      Cluster(system).join(node(controller).address)
    }
    enterBarrier(from.name + "-joined")
  }

  lazy val region = ClusterSharding(system).shardRegion(shardTypeName)

  "Inspecting cluster sharding state" must {

    "join cluster" in {
      join(controller)
      join(first)
      join(second)
      join(third)

      // make sure all nodes are up
      within(10.seconds) {
        awaitAssert {
          Cluster(system).state.members.count(_.status == MemberStatus.Up) should ===(4)
        }
      }

      runOn(controller) {
        startProxy()
      }
      runOn(first, second, third) {
        startShard()
      }

      enterBarrier("sharding started")
    }

    "return empty state when no sharded actors has started" in {

      within(10.seconds) {
        awaitAssert {
          val probe = TestProbe()
          region.tell(ShardRegion.GetClusterShardingStats(10.seconds.dilated), probe.ref)
          val shardStats = probe.expectMsgType[ShardRegion.ClusterShardingStats]
          shardStats.regions.size should ===(3)
          shardStats.regions.values.map(_.stats.size).sum should ===(0)
          shardStats.regions.keys.forall(_.hasGlobalScope) should ===(true)
        }
      }

      enterBarrier("empty sharding")
    }

    "trigger sharded actors" in {
      runOn(controller) {
        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            // trigger starting of 2 entities on first and second node
            // but leave third node without entities
            List(1, 2, 4, 6).foreach(n => region.tell(Ping(n), pingProbe.ref))
            pingProbe.receiveWhile(messages = 4) {
              case Pong => ()
            }
          }
        }
      }
      enterBarrier("sharded actors started")
    }

    "get shard state" in {
      within(10.seconds) {
        awaitAssert {
          val probe = TestProbe()
          val region = ClusterSharding(system).shardRegion(shardTypeName)
          region.tell(ShardRegion.GetClusterShardingStats(10.seconds.dilated), probe.ref)
          val regions = probe.expectMsgType[ShardRegion.ClusterShardingStats].regions
          regions.size shouldEqual 3
          regions.values.flatMap(_.stats.values).sum shouldEqual 4
          regions.keys.forall(_.hasGlobalScope) should be(true)
        }
      }
      enterBarrier("got shard state")
      system.log.info("got shard state")
    }

    "return stats after a node leaves" in {
      runOn(controller) {
        Cluster(system).leave(node(third).address)
      }

      runOn(controller, first, second) {
        within(30.seconds) {
          awaitAssert {
            Cluster(system).state.members.size should ===(3)
          }
        }
      }

      enterBarrier("third node removed")
      system.log.info("third node removed")

      runOn(controller) {
        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            // make sure we have the 4 entities still alive across the fewer nodes
            List(1, 2, 4, 6).foreach(n => region.tell(Ping(n), pingProbe.ref))
            pingProbe.receiveWhile(messages = 4) {
              case Pong => ()
            }
          }
        }
      }

      enterBarrier("shards revived")

      runOn(controller) {
        within(20.seconds) {
          awaitAssert {
            val probe = TestProbe()
            region.tell(ShardRegion.GetClusterShardingStats(20.seconds.dilated), probe.ref)
            val regions = probe.expectMsgType[ShardRegion.ClusterShardingStats].regions
            regions.size === 2
            regions.values.flatMap(_.stats.values).sum should ===(4)
          }
        }
      }

      enterBarrier("done")
    }
  }
}
