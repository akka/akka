/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit.{ TestProbe, TestDuration }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object ClusterShardingGetStatsSpec {
  case object Stop
  case class Ping(id: Long)
  case object Pong

  class ShardedActor extends Actor with ActorLogging {
    log.info(self.path.toString)
    def receive = {
      case Stop    ⇒ context.stop(self)
      case _: Ping ⇒ sender() ! Pong
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg @ Ping(id) ⇒ (id.toString, msg)
  }

  val numberOfShards = 3

  val extractShardId: ShardRegion.ExtractShardId = {
    case Ping(id) ⇒ (id % numberOfShards).toString
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
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.metrics.enabled = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.sharding {
      coordinator-failure-backoff = 3s
      shard-failure-backoff = 3s
      state-store-mode = "ddata"
    }
    """))

  nodeConfig(first, second, third)(ConfigFactory.parseString(
    """akka.cluster.roles=["shard"]"""))

}

class ClusterShardingGetStatsSpecMultiJvmNode1 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode2 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode3 extends ClusterShardingGetStatsSpec
class ClusterShardingGetStatsSpecMultiJvmNode4 extends ClusterShardingGetStatsSpec

abstract class ClusterShardingGetStatsSpec extends MultiNodeSpec(ClusterShardingGetStatsSpecConfig) with STMultiNodeSpec {

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

  var shardActor = Actor.noSender

  "Inspecting cluster sharding state" must {

    "join cluster" in {
      join(controller)
      join(first)
      join(second)
      join(third)

      // make sure all nodes has joined
      awaitAssert {
        Cluster(system).sendCurrentClusterState(testActor)
        expectMsgType[CurrentClusterState].members.size === 3
      }

      runOn(controller) {
        startProxy()
      }
      runOn(first, second, third) {
        shardActor = startShard()
      }

      enterBarrier("sharding started")
    }

    "return empty state when no sharded actors has started" in {

      within(10.seconds) {
        awaitAssert {
          val probe = TestProbe()
          val region = ClusterSharding(system).shardRegion(shardTypeName)
          region.tell(ShardRegion.GetClusterShardingStats(10.seconds.dilated), probe.ref)
          val shardStats = probe.expectMsgType[ShardRegion.ClusterShardingStats]
          shardStats.regions.size shouldEqual 3
          shardStats.regions.values.map(_.stats.size).sum shouldEqual 0
          shardStats.regions.keys.forall(_.hasGlobalScope) should be(true)
        }
      }

      enterBarrier("empty sharding")
    }

    "trigger sharded actors" in {
      runOn(controller) {
        val region = ClusterSharding(system).shardRegion(shardTypeName)

        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            // trigger starting of 2 entities on first and second node
            // but leave third node without entities
            (1 to 6).filterNot(_ % 3 == 0).foreach(n ⇒ region.tell(Ping(n), pingProbe.ref))
            pingProbe.receiveWhile(messages = 4) {
              case Pong ⇒ ()
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

    }

    "return stats after a node leaves" in {
      runOn(first) {
        Cluster(system).leave(node(third).address)
      }

      runOn(third) {
        watch(shardActor)
        expectTerminated(shardActor, 15.seconds)
      }

      enterBarrier("third node removed")

      runOn(first, second) {
        within(10.seconds) {
          awaitAssert {
            val pingProbe = TestProbe()
            // trigger the same four shards
            (1 to 6).filterNot(_ % 3 == 0).foreach(n ⇒ shardActor.tell(Ping(n), pingProbe.ref))
            pingProbe.receiveWhile(messages = 4) {
              case Pong ⇒ ()
            }
          }
        }
      }

      enterBarrier("shards revived")

      runOn(first, second) {
        within(10.seconds) {
          awaitAssert {
            val probe = TestProbe()
            val region = ClusterSharding(system).shardRegion(shardTypeName)
            region.tell(ShardRegion.GetClusterShardingStats(10.seconds.dilated), probe.ref)
            val regions = probe.expectMsgType[ShardRegion.ClusterShardingStats].regions
            regions.size shouldEqual 2
            regions.values.flatMap(_.stats.values).sum shouldEqual 4
          }
        }
      }

      enterBarrier("done")
    }
  }
}
