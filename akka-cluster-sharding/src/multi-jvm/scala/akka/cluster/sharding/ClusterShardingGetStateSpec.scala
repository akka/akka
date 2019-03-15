/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor._
import akka.cluster.{ Cluster, MultiNodeClusterSpec }
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object ClusterShardingGetStateSpec {
  case object Stop
  case class Ping(id: Long)
  case object Pong

  class ShardedActor extends Actor with ActorLogging {
    log.info(self.path.toString)
    def receive = {
      case Stop    => context.stop(self)
      case _: Ping => sender() ! Pong
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg @ Ping(id) => (id.toString, msg)
  }

  val numberOfShards = 2

  val extractShardId: ShardRegion.ExtractShardId = {
    case Ping(id) => (id % numberOfShards).toString
  }

  val shardTypeName = "Ping"
}

object ClusterShardingGetStateSpecConfig extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.sharding {
      coordinator-failure-backoff = 3s
      shard-failure-backoff = 3s
      state-store-mode = "ddata"
    }
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ClusterShardingGetStateSpec/sharding-ddata
      map-size = 10 MiB
    }
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(first, second)(ConfigFactory.parseString("""akka.cluster.roles=["shard"]"""))

}

class ClusterShardingGetStateSpecMultiJvmNode1 extends ClusterShardingGetStateSpec
class ClusterShardingGetStateSpecMultiJvmNode2 extends ClusterShardingGetStateSpec
class ClusterShardingGetStateSpecMultiJvmNode3 extends ClusterShardingGetStateSpec

abstract class ClusterShardingGetStateSpec
    extends MultiNodeSpec(ClusterShardingGetStateSpecConfig)
    with STMultiNodeSpec {

  import ClusterShardingGetStateSpec._
  import ClusterShardingGetStateSpecConfig._

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

  "Inspecting cluster sharding state" must {

    "join cluster" in {
      join(controller)
      join(first)
      join(second)

      // make sure all nodes has joined
      awaitAssert {
        Cluster(system).sendCurrentClusterState(testActor)
        expectMsgType[CurrentClusterState].members.size === 3
      }

      runOn(controller) {
        startProxy()
      }
      runOn(first, second) {
        startShard()
      }

      enterBarrier("sharding started")
    }

    "return empty state when no sharded actors has started" in {

      awaitAssert {
        val probe = TestProbe()
        val region = ClusterSharding(system).shardRegion(shardTypeName)
        region.tell(ShardRegion.GetCurrentRegions, probe.ref)
        probe.expectMsgType[ShardRegion.CurrentRegions].regions.size === 0
      }

      enterBarrier("empty sharding")
    }

    "trigger sharded actors" in {
      runOn(controller) {
        val region = ClusterSharding(system).shardRegion(shardTypeName)

        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            // trigger starting of 4 entities
            (1 to 4).foreach(n => region.tell(Ping(n), pingProbe.ref))
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
          region.tell(ShardRegion.GetCurrentRegions, probe.ref)
          val regions = probe.expectMsgType[ShardRegion.CurrentRegions].regions
          regions.size === 2
          regions.foreach { region =>
            val path = RootActorPath(region) / "system" / "sharding" / shardTypeName

            system.actorSelection(path).tell(ShardRegion.GetShardRegionState, probe.ref)
          }
          val states = probe.receiveWhile(messages = regions.size) {
            case msg: ShardRegion.CurrentShardRegionState => msg
          }
          val allEntityIds = for {
            state <- states
            shard <- state.shards
            entityId <- shard.entityIds
          } yield entityId

          allEntityIds.toSet === Set("1", "2", "3", "4")
        }
      }

      enterBarrier("done")

    }
  }
}
