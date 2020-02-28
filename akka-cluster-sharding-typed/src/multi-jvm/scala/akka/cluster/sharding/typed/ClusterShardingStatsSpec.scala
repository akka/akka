/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import akka.cluster.MultiNodeClusterSpec
import akka.cluster.sharding.ShardRegion.ClusterShardingStats
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityTypeKey }
import akka.cluster.typed.MultiNodeTypedClusterSpec
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

object ClusterShardingStatsSpecConfig extends MultiNodeConfig {

  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
        akka.log-dead-letters-during-shutdown = off
        akka.cluster.sharding.updating-state-timeout = 2s
        akka.cluster.sharding.waiting-for-state-timeout = 2s
      """).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterShardingStatsSpecMultiJvmNode1 extends ClusterShardingStatsSpec
class ClusterShardingStatsSpecMultiJvmNode2 extends ClusterShardingStatsSpec
class ClusterShardingStatsSpecMultiJvmNode3 extends ClusterShardingStatsSpec

object Pinger {
  sealed trait Command extends CborSerializable
  case class Ping(id: Int, ref: ActorRef[Pong]) extends Command
  case class Pong(id: Int) extends CborSerializable

  def apply(): Behavior[Command] = {
    Behaviors.receiveMessage[Command] {
      case Ping(id: Int, ref) =>
        ref ! Pong(id)
        Behaviors.same
    }
  }

}

abstract class ClusterShardingStatsSpec
    extends MultiNodeSpec(ClusterShardingStatsSpecConfig)
    with MultiNodeTypedClusterSpec
    with ScalaFutures {

  import ClusterShardingStatsSpecConfig._
  import Pinger._

  val typeKey = EntityTypeKey[Command]("ping")

  val entityId = "ping-1"

  val sharding = ClusterSharding(typedSystem)

  val settings = ClusterShardingSettings(typedSystem)

  val queryTimeout = settings.shardRegionQueryTimeout * roles.size.toLong //numeric widening y'all

  "Cluster sharding stats" must {
    "form cluster" in {
      formCluster(first, second, third)
    }

    "get shard stats" in {

      sharding.init(Entity(typeKey)(_ => Pinger()))
      enterBarrier("sharding started")

      import akka.actor.typed.scaladsl.adapter._
      val entityRef = ClusterSharding(system.toTyped).entityRefFor(typeKey, entityId)
      val pongProbe = TestProbe[Pong]

      entityRef ! Ping(1, pongProbe.ref)
      pongProbe.expectMessageType[Pong]
      enterBarrier("sharding-initialized")

      runOn(first, second, third) {
        val replyToProbe = TestProbe[ClusterShardingStats]()
        sharding.shardState ! GetClusterShardingStats(queryTimeout, replyToProbe.ref)

        val stats = replyToProbe.expectMessageType[ClusterShardingStats](queryTimeout)
        stats.regions.size shouldEqual 3
        stats.regions.values.flatMap(_.stats.values).sum shouldEqual 1
      }
      enterBarrier("done")

    }

  }

}
