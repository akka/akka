/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.MultiNodeClusterSpec
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._
import com.typesafe.config.ConfigFactory

/**
 * one-to-one mapping between shards and entities is not efficient but some use that anyway
 */
object ClusterShardingSingleShardPerEntitySpec {
  class Entity extends Actor {
    def receive = {
      case id: Int => sender() ! id
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int => (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case id: Int => id.toString
  }

}

object ClusterShardingSingleShardPerEntitySpecConfig extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.cluster.sharding.state-store-mode = ddata
    akka.cluster.sharding.updating-state-timeout = 1s
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)
}

class ClusterShardingSingleShardPerEntitySpecMultiJvmNode1 extends ClusterShardingSingleShardPerEntitySpec
class ClusterShardingSingleShardPerEntitySpecMultiJvmNode2 extends ClusterShardingSingleShardPerEntitySpec
class ClusterShardingSingleShardPerEntitySpecMultiJvmNode3 extends ClusterShardingSingleShardPerEntitySpec
class ClusterShardingSingleShardPerEntitySpecMultiJvmNode4 extends ClusterShardingSingleShardPerEntitySpec
class ClusterShardingSingleShardPerEntitySpecMultiJvmNode5 extends ClusterShardingSingleShardPerEntitySpec

abstract class ClusterShardingSingleShardPerEntitySpec
    extends MultiNodeSpec(ClusterShardingSingleShardPerEntitySpecConfig)
    with STMultiNodeSpec
    with ImplicitSender {
  import ClusterShardingSingleShardPerEntitySpec._
  import ClusterShardingSingleShardPerEntitySpecConfig._

  override def initialParticipants = roles.size

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system).join(node(to).address)
      startSharding()
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = Props[Entity],
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  def joinAndAllocate(node: RoleName, entityId: Int): Unit = {
    within(10.seconds) {
      join(node, first)
      runOn(node) {
        region ! entityId
        expectMsg(entityId)
        lastSender.path should be(region.path / s"$entityId" / s"$entityId")
      }
    }
    enterBarrier(s"started-$entityId")
  }

  s"Cluster sharding with single shard per entity" must {

    "use specified region" in {
      joinAndAllocate(first, 1)
      joinAndAllocate(second, 2)
      joinAndAllocate(third, 3)
      joinAndAllocate(fourth, 4)
      joinAndAllocate(fifth, 5)

      runOn(first) {
        // coordinator is on 'first', blackhole 3 other means that it can't update with WriteMajority
        testConductor.blackhole(first, third, Direction.Both).await
        testConductor.blackhole(first, fourth, Direction.Both).await
        testConductor.blackhole(first, fifth, Direction.Both).await

        // shard 6 not allocated yet and due to the blackhole it will not be completed
        region ! 6

        // shard 1 location is know by 'first' region, not involving coordinator
        region ! 1
        expectMsg(1)

        // shard 2 location not known at 'first' region yet, but coordinator is on 'first' and should
        // be able to answer GetShardHome even though previous request for shard 4 has not completed yet
        region ! 2
        expectMsg(2)
        lastSender.path should be(node(second) / "system" / "sharding" / "Entity" / "2" / "2")

        testConductor.passThrough(first, third, Direction.Both).await
        testConductor.passThrough(first, fourth, Direction.Both).await
        testConductor.passThrough(first, fifth, Direction.Both).await
        expectMsg(10.seconds, 6)
      }

      enterBarrier("after-1")
    }

  }
}
