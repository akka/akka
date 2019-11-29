/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Address
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MultiNodeClusterSpec
import akka.cluster.sharding.DynamicShardAllocationSpec.GiveMeYourHome.Get
import akka.cluster.sharding.DynamicShardAllocationSpec.GiveMeYourHome.Home
import akka.cluster.sharding.dynamic.DynamicShardAllocation
import akka.cluster.sharding.dynamic.DynamicShardAllocationStrategy
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

object DynamicShardAllocationSpecConfig extends MultiNodeConfig {

  commonConfig(ConfigFactory.parseString("""
      akka.loglevel = INFO
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
}

class DynamicShardAllocationSpecMultiJvmNode1 extends DynamicShardAllocationSpec
class DynamicShardAllocationSpecMultiJvmNode2 extends DynamicShardAllocationSpec
class DynamicShardAllocationSpecMultiJvmNode3 extends DynamicShardAllocationSpec
class DynamicShardAllocationSpecMultiJvmNode4 extends DynamicShardAllocationSpec

object DynamicShardAllocationSpec {

  object GiveMeYourHome {
    case class Get(id: String)
    case class Home(address: Address)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case g @ Get(id) => (id, g)
    }

    // shard == id to make testing easier
    val extractShardId: ShardRegion.ExtractShardId = {
      case Get(id) => id
    }
  }

  class GiveMeYourHome extends Actor with ActorLogging {

    val selfAddress = Cluster(context.system).selfAddress

    log.info("Started on {}", selfAddress)

    override def receive: Receive = {
      case Get(_) =>
        sender() ! Home(selfAddress)
    }
  }
}

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
  val initiallyOnForth = "on-forth"

  "Dynamic shard allocation" must {
    "form cluster" in {
      awaitClusterUp(first, second, third)
      enterBarrier("cluster-started")
    }

    lazy val shardRegion = {
      ClusterSharding(system).start(
        typeName = typeName,
        entityProps = Props[GiveMeYourHome],
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId,
        new DynamicShardAllocationStrategy(system, typeName),
        PoisonPill)
    }

    "start cluster sharding" in {
      shardRegion
      enterBarrier("shard-region-started")
    }

    "default to allocating a shard to the local shard region" in {
      runOn(first, second, third) {
        shardRegion ! Get(myself.name)
        val actorLocation = expectMsgType[Home](20.seconds).address
        actorLocation shouldEqual Cluster(system).selfAddress
      }
      enterBarrier("local-message-sent")
    }

    "move shard via distributed data" in {
      val shardToSpecifyLocation = "cats"
      runOn(first) {
        DynamicShardAllocation(system)
          .clientFor(typeName)
          .updateShardLocation(shardToSpecifyLocation, Cluster(system).selfAddress)
          .futureValue
      }
      enterBarrier("shard-location-updated")

      runOn(second, third) {
        val probe = TestProbe()
        awaitAssert({
          shardRegion.tell(Get(shardToSpecifyLocation), probe.ref)
          probe.expectMsg(Home(address(first)))
        }, 10.seconds)
      }
      enterBarrier("shard-allocated-to-specific-node")
    }

    "allocate to a node that does not exist yet" in {
      val onForthShardId = "on-forth"
      val forthAddress = address(forth)
      runOn(second) {
        system.log.info("Allocating {} on {}", onForthShardId, forthAddress)
        DynamicShardAllocation(system).clientFor(typeName).updateShardLocation(onForthShardId, forthAddress)
      }
      enterBarrier("allocated-to-new-node")
      runOn(forth) {
        joinWithin(first)
      }
      enterBarrier("forth-node-joined")
      runOn(first, second, third) {
        awaitAssert({
          shardRegion ! Get(initiallyOnForth)
          expectMsg(Home(address(forth)))
        }, 10.seconds)
      }
      enterBarrier("shard-allocated-to-forth")
    }

    "move allocation" in {
      runOn(third) {
        system.log.info("Moving shard from forth to first: {}", address(first))
        DynamicShardAllocation(system).clientFor(typeName).updateShardLocation(initiallyOnForth, address(first))
      }
      enterBarrier("shard-moved-from-forth-to-first")
      runOn(first, second, third, forth) {
        awaitAssert({
          shardRegion ! Get(initiallyOnForth)
          expectMsg(Home(address(first)))
        }, 10.seconds)
      }
      enterBarrier("finished")
    }

    "continue to work when shard coordinator is moved" in {
      pending
    }
  }
}
