/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import scala.concurrent.duration._
import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.typed.{ MultiDcClusterActors, MultiNodeTypedClusterSpec }
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.cluster.MultiNodeClusterSpec
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

object MultiDcClusterShardingSpecConfig extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(ConfigFactory.parseString("""
        akka.loglevel = DEBUG
        akka.cluster.sharding {
          number-of-shards = 10
          # First is likely to be ignored as shard coordinator not ready
          retry-interval = 0.2s
        }
      """).withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(first, second)(ConfigFactory.parseString("""
      akka.cluster.multi-data-center.self-data-center = "dc1"
    """))

  nodeConfig(third, fourth)(ConfigFactory.parseString("""
      akka.cluster.multi-data-center.self-data-center = "dc2"
    """))

  testTransport(on = true)
}

class MultiDcClusterShardingMultiJvmNode1 extends MultiDcClusterShardingSpec
class MultiDcClusterShardingMultiJvmNode2 extends MultiDcClusterShardingSpec
class MultiDcClusterShardingMultiJvmNode3 extends MultiDcClusterShardingSpec
class MultiDcClusterShardingMultiJvmNode4 extends MultiDcClusterShardingSpec

abstract class MultiDcClusterShardingSpec
    extends MultiNodeSpec(MultiDcClusterShardingSpecConfig)
    with MultiNodeTypedClusterSpec
    with ScalaFutures {

  import MultiDcClusterShardingSpecConfig._
  import MultiDcClusterActors._

  val typeKey = EntityTypeKey[PingProtocol]("ping")
  val entityId = "ping-1"

  "Cluster sharding in multi dc cluster" must {
    "form cluster" in {
      formCluster(first, second, third, fourth)
    }

    "init sharding" in {
      val sharding = ClusterSharding(typedSystem)
      val shardRegion: ActorRef[ShardingEnvelope[PingProtocol]] = sharding.init(Entity(typeKey, _ => multiDcPinger))
      val probe = TestProbe[Pong]
      shardRegion ! ShardingEnvelope(entityId, Ping(probe.ref))
      probe.expectMessage(max = 10.seconds, Pong(cluster.selfMember.dataCenter))
      enterBarrier("sharding-initialized")
    }

    "be able to message via entity ref" in {
      val probe = TestProbe[Pong]
      val entityRef = ClusterSharding(typedSystem).entityRefFor(typeKey, entityId)
      entityRef ! Ping(probe.ref)
      probe.expectMessage(Pong(cluster.selfMember.dataCenter))
      enterBarrier("entity-ref")
    }
  }

  "be able to ask via entity ref" in {
    implicit val timeout = Timeout(remainingOrDefault)
    val entityRef = ClusterSharding(typedSystem).entityRefFor(typeKey, entityId)
    val response = entityRef ? Ping
    response.futureValue shouldEqual Pong(cluster.selfMember.dataCenter)
    enterBarrier("ask")
  }

  "be able to message cross dc via proxy" in {
    runOn(first, second) {
      val proxy: ActorRef[ShardingEnvelope[PingProtocol]] = ClusterSharding(typedSystem).init(
        Entity(typeKey, _ => multiDcPinger).withSettings(ClusterShardingSettings(typedSystem).withDataCenter("dc2")))
      val probe = TestProbe[Pong]
      proxy ! ShardingEnvelope(entityId, Ping(probe.ref))
      probe.expectMessage(remainingOrDefault, Pong("dc2"))
    }
    enterBarrier("done")
  }
}
