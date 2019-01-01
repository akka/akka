/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.protobuf

import akka.actor.ExtendedActorSystem
import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.{ Shard, ShardCoordinator, ShardRegion }

class ClusterShardingMessageSerializerSpec extends AkkaSpec {
  import ShardCoordinator.Internal._

  val serializer = new ClusterShardingMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  val region1 = system.actorOf(Props.empty, "region1")
  val region2 = system.actorOf(Props.empty, "region2")
  val region3 = system.actorOf(Props.empty, "region3")
  val regionProxy1 = system.actorOf(Props.empty, "regionProxy1")
  val regionProxy2 = system.actorOf(Props.empty, "regionProxy2")

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, serializer.manifest(obj))
    ref should ===(obj)
  }

  "ClusterShardingMessageSerializer" must {

    "be able to serialize ShardCoordinator snapshot State" in {
      val state = State(
        shards = Map("a" → region1, "b" → region2, "c" → region2),
        regions = Map(region1 → Vector("a"), region2 → Vector("b", "c"), region3 → Vector.empty[String]),
        regionProxies = Set(regionProxy1, regionProxy2),
        unallocatedShards = Set("d"))
      checkSerialization(state)
    }

    "be able to serialize ShardCoordinator domain events" in {
      checkSerialization(ShardRegionRegistered(region1))
      checkSerialization(ShardRegionProxyRegistered(regionProxy1))
      checkSerialization(ShardRegionTerminated(region1))
      checkSerialization(ShardRegionProxyTerminated(regionProxy1))
      checkSerialization(ShardHomeAllocated("a", region1))
      checkSerialization(ShardHomeDeallocated("a"))
    }

    "be able to serialize ShardCoordinator remote messages" in {
      checkSerialization(Register(region1))
      checkSerialization(RegisterProxy(regionProxy1))
      checkSerialization(RegisterAck(region1))
      checkSerialization(GetShardHome("a"))
      checkSerialization(ShardHome("a", region1))
      checkSerialization(HostShard("a"))
      checkSerialization(ShardStarted("a"))
      checkSerialization(BeginHandOff("a"))
      checkSerialization(BeginHandOffAck("a"))
      checkSerialization(HandOff("a"))
      checkSerialization(ShardStopped("a"))
      checkSerialization(GracefulShutdownReq(region1))
    }

    "be able to serialize PersistentShard snapshot state" in {
      checkSerialization(Shard.State(Set("e1", "e2", "e3")))
    }

    "be able to serialize PersistentShard domain events" in {
      checkSerialization(Shard.EntityStarted("e1"))
      checkSerialization(Shard.EntityStopped("e1"))
    }

    "be able to serialize GetShardStats" in {
      checkSerialization(Shard.GetShardStats)
    }

    "be able to serialize ShardStats" in {
      checkSerialization(Shard.ShardStats("a", 23))
    }

    "be able to serialize GetShardRegionStats" in {
      checkSerialization(ShardRegion.GetShardRegionStats)
    }

    "be able to serialize ShardRegionStats" in {
      checkSerialization(ShardRegion.ShardRegionStats(Map.empty[ShardId, Int]))
      checkSerialization(ShardRegion.ShardRegionStats(Map[ShardId, Int]("a" -> 23)))
    }

    "be able to serialize StartEntity" in {
      checkSerialization(ShardRegion.StartEntity("42"))
      checkSerialization(ShardRegion.StartEntityAck("13", "37"))
    }
  }
}
