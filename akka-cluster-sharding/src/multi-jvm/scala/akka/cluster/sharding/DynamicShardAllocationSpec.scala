/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.Actor
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MultiNodeClusterSpec
import akka.cluster.UniqueAddress
import akka.cluster.sharding.DynamicShardAllocationSpec.GiveMeYourHome.Get
import akka.cluster.sharding.DynamicShardAllocationSpec.GiveMeYourHome.Home
import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategy
import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategyClient
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import com.github.ghik.silencer.silent
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

object DynamicShardAllocationSpecConfig extends MultiNodeConfig {

  commonConfig(ConfigFactory.parseString("""
      akka.loglevel = info
      akka.actor.provider = "cluster"
      // FIXME create protobuf for serialization
      akka.actor.allow-java-serialization = on
      akka.cluster.sharding {
        distributed-data.durable.lmdb {
          dir = target/DynamicShardAllocationSpec/sharding-ddata
          map-size = 10 MiB
        }
        retry-interval = 2000ms
        waiting-for-state-timeout = 2000ms
        rebalance-interval = 1s
      }
     """).withFallback(MultiNodeClusterSpec.clusterConfig))

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val forth = role("forth")
//  val fifth = role("fifth")

}

class DynamicShardAllocationSpecMultiJvmNode1 extends DynamicShardAllocationSpec
class DynamicShardAllocationSpecMultiJvmNode2 extends DynamicShardAllocationSpec
class DynamicShardAllocationSpecMultiJvmNode3 extends DynamicShardAllocationSpec
class DynamicShardAllocationSpecMultiJvmNode4 extends DynamicShardAllocationSpec

object DynamicShardAllocationSpec {

  object GiveMeYourHome {
    case class Get(id: String)
    case class Home(address: UniqueAddress)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case g @ Get(id) => (id, g)
    }

    // shard == id to make testing easier
    val extractShardId: ShardRegion.ExtractShardId = {
      case Get(id) => id
    }
  }

  class GiveMeYourHome extends Actor {

    val selfAddress = Cluster(context.system).selfUniqueAddress

    override def receive: Receive = {
      case Get(_) =>
        sender() ! Home(selfAddress)
    }
  }

}

@silent
abstract class DynamicShardAllocationSpec
    extends MultiNodeSpec(DynamicShardAllocationSpecConfig)
    with MultiNodeClusterSpec
    with ImplicitSender
    with ScalaFutures {

  import DynamicShardAllocationSpecConfig._
  import DynamicShardAllocationSpec._
  import DynamicShardAllocationSpec.GiveMeYourHome._

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.second)

  val typeName = "home"

  "Dynamic shard allocation" must {
    "form cluster" in {
      awaitClusterUp(first, second, third)
      enterBarrier("cluster-started")
    }

    val allocationStrategy = new DynamicShardAllocationStrategy(system, typeName)

    def shardRegion() = {
      ClusterSharding(system).start(
        typeName = typeName,
        entityProps = Props[GiveMeYourHome],
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId,
        allocationStrategy,
        PoisonPill)
    }

    def uniqueAddress(role: RoleName) = {
      cluster.readView.members
        .find { m =>
          m.address == address(role)
        }
        .getOrElse(throw new RuntimeException(s"Could not find ${address(role)} in ${cluster.readView.members}"))
        .uniqueAddress
    }

    "start cluster sharding" in {
      shardRegion()
      enterBarrier("shard-region-started")
    }

    "default to allocating a shard to the local shard region" in {
      runOn(first, second, third) {
        shardRegion() ! Get(myself.name)
        val actorLocation = expectMsgType[Home](10.seconds).address
        actorLocation shouldEqual Cluster(system).selfUniqueAddress
      }
      enterBarrier("local-message-sent")
    }

    "move shard via distributed data" in {
      // TODO put a small API around updating to not expose the
      // exact DData message as public API
      val shardToSpecifyLocation = "cats"
      val client = new DynamicShardAllocationStrategyClient(system, typeName)
      runOn(first) {
        client.updateShardLocation(shardToSpecifyLocation, Cluster(system).selfAddress).futureValue
      }
      enterBarrier("shard-location-updated")

      runOn(second, third) {
        awaitAssert({
          shardRegion() ! Get(shardToSpecifyLocation)
          expectMsg(Home(uniqueAddress(first)))
        }, 10.seconds)
      }
      enterBarrier("shard-allocated-to-specific-node")
    }

    "allocate to a node that does not exist yet" in {
      val onForthShardId = "on-forth"
      val client = new DynamicShardAllocationStrategyClient(system, typeName)
      val forthAddress = address(forth)
      runOn(second) {
        system.log.info("Allocating {} on {}", onForthShardId, forthAddress)
        client.updateShardLocation(onForthShardId, forthAddress)
      }
      enterBarrier("allocated-to-new-node")
      runOn(forth) {
        joinWithin(first)
      }
      enterBarrier("forth-node-joined")
      runOn(first, second, third) {
        awaitAssert({
          shardRegion() ! Get(onForthShardId)
          expectMsg(Home(uniqueAddress(forth)))
        }, 10.seconds)
      }
      enterBarrier("shard-allocated-to-forth")
    }
  }
}
