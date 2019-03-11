/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.protobuf

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable
import akka.actor.ActorRef
import akka.actor.ExtendedActorSystem
import akka.cluster.sharding.Shard
import akka.cluster.sharding.ShardCoordinator
import akka.cluster.sharding.protobuf.msg.{ ClusterShardingMessages => sm }
import akka.serialization.BaseSerializer
import akka.serialization.Serialization
import akka.serialization.SerializerWithStringManifest
import akka.protobuf.MessageLite
import akka.util.ccompat._
import java.io.NotSerializableException

import akka.cluster.sharding.ShardRegion._

/**
 * INTERNAL API: Protobuf serializer of ClusterSharding messages.
 */
private[akka] class ClusterShardingMessageSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {
  import ShardCoordinator.Internal._
  import Shard.{ GetShardStats, ShardStats }
  import Shard.{ State => EntityState, EntityStarted, EntityStopped }

  private final val BufferSize = 1024 * 4

  private val CoordinatorStateManifest = "AA"
  private val ShardRegionRegisteredManifest = "AB"
  private val ShardRegionProxyRegisteredManifest = "AC"
  private val ShardRegionTerminatedManifest = "AD"
  private val ShardRegionProxyTerminatedManifest = "AE"
  private val ShardHomeAllocatedManifest = "AF"
  private val ShardHomeDeallocatedManifest = "AG"

  private val RegisterManifest = "BA"
  private val RegisterProxyManifest = "BB"
  private val RegisterAckManifest = "BC"
  private val GetShardHomeManifest = "BD"
  private val ShardHomeManifest = "BE"
  private val HostShardManifest = "BF"
  private val ShardStartedManifest = "BG"
  private val BeginHandOffManifest = "BH"
  private val BeginHandOffAckManifest = "BI"
  private val HandOffManifest = "BJ"
  private val ShardStoppedManifest = "BK"
  private val GracefulShutdownReqManifest = "BL"

  private val EntityStateManifest = "CA"
  private val EntityStartedManifest = "CB"
  private val EntityStoppedManifest = "CD"

  private val StartEntityManifest = "EA"
  private val StartEntityAckManifest = "EB"

  private val GetShardStatsManifest = "DA"
  private val ShardStatsManifest = "DB"
  private val GetShardRegionStatsManifest = "DC"
  private val ShardRegionStatsManifest = "DD"

  private val fromBinaryMap = collection.immutable.HashMap[String, Array[Byte] => AnyRef](
    EntityStateManifest -> entityStateFromBinary,
    EntityStartedManifest -> entityStartedFromBinary,
    EntityStoppedManifest -> entityStoppedFromBinary,
    CoordinatorStateManifest -> coordinatorStateFromBinary,
    ShardRegionRegisteredManifest -> { bytes =>
      ShardRegionRegistered(actorRefMessageFromBinary(bytes))
    },
    ShardRegionProxyRegisteredManifest -> { bytes =>
      ShardRegionProxyRegistered(actorRefMessageFromBinary(bytes))
    },
    ShardRegionTerminatedManifest -> { bytes =>
      ShardRegionTerminated(actorRefMessageFromBinary(bytes))
    },
    ShardRegionProxyTerminatedManifest -> { bytes =>
      ShardRegionProxyTerminated(actorRefMessageFromBinary(bytes))
    },
    ShardHomeAllocatedManifest -> shardHomeAllocatedFromBinary,
    ShardHomeDeallocatedManifest -> { bytes =>
      ShardHomeDeallocated(shardIdMessageFromBinary(bytes))
    },
    RegisterManifest -> { bytes =>
      Register(actorRefMessageFromBinary(bytes))
    },
    RegisterProxyManifest -> { bytes =>
      RegisterProxy(actorRefMessageFromBinary(bytes))
    },
    RegisterAckManifest -> { bytes =>
      RegisterAck(actorRefMessageFromBinary(bytes))
    },
    GetShardHomeManifest -> { bytes =>
      GetShardHome(shardIdMessageFromBinary(bytes))
    },
    ShardHomeManifest -> shardHomeFromBinary,
    HostShardManifest -> { bytes =>
      HostShard(shardIdMessageFromBinary(bytes))
    },
    ShardStartedManifest -> { bytes =>
      ShardStarted(shardIdMessageFromBinary(bytes))
    },
    BeginHandOffManifest -> { bytes =>
      BeginHandOff(shardIdMessageFromBinary(bytes))
    },
    BeginHandOffAckManifest -> { bytes =>
      BeginHandOffAck(shardIdMessageFromBinary(bytes))
    },
    HandOffManifest -> { bytes =>
      HandOff(shardIdMessageFromBinary(bytes))
    },
    ShardStoppedManifest -> { bytes =>
      ShardStopped(shardIdMessageFromBinary(bytes))
    },
    GracefulShutdownReqManifest -> { bytes =>
      GracefulShutdownReq(actorRefMessageFromBinary(bytes))
    },
    GetShardStatsManifest -> { bytes =>
      GetShardStats
    },
    ShardStatsManifest -> { bytes =>
      shardStatsFromBinary(bytes)
    },
    GetShardRegionStatsManifest -> { bytes =>
      GetShardRegionStats
    },
    ShardRegionStatsManifest -> { bytes =>
      shardRegionStatsFromBinary(bytes)
    },
    StartEntityManifest -> { startEntityFromBinary(_) },
    StartEntityAckManifest -> { startEntityAckFromBinary(_) })

  override def manifest(obj: AnyRef): String = obj match {
    case _: EntityState   => EntityStateManifest
    case _: EntityStarted => EntityStartedManifest
    case _: EntityStopped => EntityStoppedManifest

    case _: State                      => CoordinatorStateManifest
    case _: ShardRegionRegistered      => ShardRegionRegisteredManifest
    case _: ShardRegionProxyRegistered => ShardRegionProxyRegisteredManifest
    case _: ShardRegionTerminated      => ShardRegionTerminatedManifest
    case _: ShardRegionProxyTerminated => ShardRegionProxyTerminatedManifest
    case _: ShardHomeAllocated         => ShardHomeAllocatedManifest
    case _: ShardHomeDeallocated       => ShardHomeDeallocatedManifest

    case _: Register            => RegisterManifest
    case _: RegisterProxy       => RegisterProxyManifest
    case _: RegisterAck         => RegisterAckManifest
    case _: GetShardHome        => GetShardHomeManifest
    case _: ShardHome           => ShardHomeManifest
    case _: HostShard           => HostShardManifest
    case _: ShardStarted        => ShardStartedManifest
    case _: BeginHandOff        => BeginHandOffManifest
    case _: BeginHandOffAck     => BeginHandOffAckManifest
    case _: HandOff             => HandOffManifest
    case _: ShardStopped        => ShardStoppedManifest
    case _: GracefulShutdownReq => GracefulShutdownReqManifest

    case _: StartEntity    => StartEntityManifest
    case _: StartEntityAck => StartEntityAckManifest

    case GetShardStats       => GetShardStatsManifest
    case _: ShardStats       => ShardStatsManifest
    case GetShardRegionStats => GetShardRegionStatsManifest
    case _: ShardRegionStats => ShardRegionStatsManifest
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: State                        => compress(coordinatorStateToProto(m))
    case ShardRegionRegistered(ref)      => actorRefMessageToProto(ref).toByteArray
    case ShardRegionProxyRegistered(ref) => actorRefMessageToProto(ref).toByteArray
    case ShardRegionTerminated(ref)      => actorRefMessageToProto(ref).toByteArray
    case ShardRegionProxyTerminated(ref) => actorRefMessageToProto(ref).toByteArray
    case m: ShardHomeAllocated           => shardHomeAllocatedToProto(m).toByteArray
    case ShardHomeDeallocated(shardId)   => shardIdMessageToProto(shardId).toByteArray

    case Register(ref)            => actorRefMessageToProto(ref).toByteArray
    case RegisterProxy(ref)       => actorRefMessageToProto(ref).toByteArray
    case RegisterAck(ref)         => actorRefMessageToProto(ref).toByteArray
    case GetShardHome(shardId)    => shardIdMessageToProto(shardId).toByteArray
    case m: ShardHome             => shardHomeToProto(m).toByteArray
    case HostShard(shardId)       => shardIdMessageToProto(shardId).toByteArray
    case ShardStarted(shardId)    => shardIdMessageToProto(shardId).toByteArray
    case BeginHandOff(shardId)    => shardIdMessageToProto(shardId).toByteArray
    case BeginHandOffAck(shardId) => shardIdMessageToProto(shardId).toByteArray
    case HandOff(shardId)         => shardIdMessageToProto(shardId).toByteArray
    case ShardStopped(shardId)    => shardIdMessageToProto(shardId).toByteArray
    case GracefulShutdownReq(ref) =>
      actorRefMessageToProto(ref).toByteArray

    case m: EntityState   => entityStateToProto(m).toByteArray
    case m: EntityStarted => entityStartedToProto(m).toByteArray
    case m: EntityStopped => entityStoppedToProto(m).toByteArray

    case s: StartEntity    => startEntityToByteArray(s)
    case s: StartEntityAck => startEntityAckToByteArray(s)

    case GetShardStats       => Array.emptyByteArray
    case m: ShardStats       => shardStatsToProto(m).toByteArray
    case GetShardRegionStats => Array.emptyByteArray
    case m: ShardRegionStats => shardRegionStatsToProto(m).toByteArray

    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) => f(bytes)
      case None =>
        throw new NotSerializableException(
          s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
    }

  private def coordinatorStateToProto(state: State): sm.CoordinatorState = {
    val regions = state.regions
      .map {
        case (regionRef, _) => Serialization.serializedActorPath(regionRef)
      }
      .toVector
      .asJava

    val builder = sm.CoordinatorState.newBuilder()

    state.shards.foreach {
      case (shardId, regionRef) =>
        val b = sm.CoordinatorState.ShardEntry
          .newBuilder()
          .setShardId(shardId)
          .setRegionRef(Serialization.serializedActorPath(regionRef))
        builder.addShards(b)
    }
    state.regions.foreach {
      case (regionRef, _) => builder.addRegions(Serialization.serializedActorPath(regionRef))
    }
    state.regionProxies.foreach { ref =>
      builder.addRegionProxies(Serialization.serializedActorPath(ref))
    }
    state.unallocatedShards.foreach { builder.addUnallocatedShards }

    builder.build()
  }

  private def coordinatorStateFromBinary(bytes: Array[Byte]): State =
    coordinatorStateFromProto(sm.CoordinatorState.parseFrom(decompress(bytes)))

  private def coordinatorStateFromProto(state: sm.CoordinatorState): State = {
    val shards: Map[String, ActorRef] =
      state.getShardsList.asScala.toVector.iterator.map { entry =>
        entry.getShardId -> resolveActorRef(entry.getRegionRef)
      }.toMap

    val regionsZero: Map[ActorRef, Vector[String]] =
      state.getRegionsList.asScala.toVector.iterator.map(resolveActorRef(_) -> Vector.empty[String]).toMap
    val regions: Map[ActorRef, Vector[String]] =
      shards.foldLeft(regionsZero) {
        case (acc, (shardId, regionRef)) => acc.updated(regionRef, acc(regionRef) :+ shardId)
      }

    val proxies: Set[ActorRef] = state.getRegionProxiesList.asScala.iterator.map { resolveActorRef }.to(immutable.Set)
    val unallocatedShards: Set[String] = state.getUnallocatedShardsList.asScala.toSet

    State(shards, regions, proxies, unallocatedShards)
  }

  private def actorRefMessageToProto(ref: ActorRef): sm.ActorRefMessage =
    sm.ActorRefMessage.newBuilder().setRef(Serialization.serializedActorPath(ref)).build()

  private def actorRefMessageFromBinary(bytes: Array[Byte]): ActorRef =
    resolveActorRef(sm.ActorRefMessage.parseFrom(bytes).getRef)

  private def shardIdMessageToProto(shardId: String): sm.ShardIdMessage =
    sm.ShardIdMessage.newBuilder().setShard(shardId).build()

  private def shardIdMessageFromBinary(bytes: Array[Byte]): String =
    sm.ShardIdMessage.parseFrom(bytes).getShard

  private def shardHomeAllocatedToProto(evt: ShardHomeAllocated): sm.ShardHomeAllocated =
    sm.ShardHomeAllocated
      .newBuilder()
      .setShard(evt.shard)
      .setRegion(Serialization.serializedActorPath(evt.region))
      .build()

  private def shardHomeAllocatedFromBinary(bytes: Array[Byte]): ShardHomeAllocated = {
    val m = sm.ShardHomeAllocated.parseFrom(bytes)
    ShardHomeAllocated(m.getShard, resolveActorRef(m.getRegion))
  }

  private def shardHomeToProto(m: ShardHome): sm.ShardHome =
    sm.ShardHome.newBuilder().setShard(m.shard).setRegion(Serialization.serializedActorPath(m.ref)).build()

  private def shardHomeFromBinary(bytes: Array[Byte]): ShardHome = {
    val m = sm.ShardHome.parseFrom(bytes)
    ShardHome(m.getShard, resolveActorRef(m.getRegion))
  }

  private def entityStateToProto(m: EntityState): sm.EntityState = {
    val b = sm.EntityState.newBuilder()
    m.entities.foreach(b.addEntities)
    b.build()
  }

  private def entityStateFromBinary(bytes: Array[Byte]): EntityState =
    EntityState(sm.EntityState.parseFrom(bytes).getEntitiesList.asScala.toSet)

  private def entityStartedToProto(evt: EntityStarted): sm.EntityStarted =
    sm.EntityStarted.newBuilder().setEntityId(evt.entityId).build()

  private def entityStartedFromBinary(bytes: Array[Byte]): EntityStarted =
    EntityStarted(sm.EntityStarted.parseFrom(bytes).getEntityId)

  private def entityStoppedToProto(evt: EntityStopped): sm.EntityStopped =
    sm.EntityStopped.newBuilder().setEntityId(evt.entityId).build()

  private def entityStoppedFromBinary(bytes: Array[Byte]): EntityStopped =
    EntityStopped(sm.EntityStopped.parseFrom(bytes).getEntityId)

  private def shardStatsToProto(evt: ShardStats): sm.ShardStats =
    sm.ShardStats.newBuilder().setShard(evt.shardId).setEntityCount(evt.entityCount).build()

  private def shardStatsFromBinary(bytes: Array[Byte]): ShardStats = {
    val parsed = sm.ShardStats.parseFrom(bytes)
    ShardStats(parsed.getShard, parsed.getEntityCount)
  }

  private def shardRegionStatsToProto(evt: ShardRegionStats): sm.ShardRegionStats = {
    val b = sm.ShardRegionStats.newBuilder()
    evt.stats.foreach {
      case (sid, no) =>
        b.addStats(sm.MapFieldEntry.newBuilder().setKey(sid).setValue(no).build())
    }
    b.build()
  }

  private def shardRegionStatsFromBinary(bytes: Array[Byte]): ShardRegionStats = {
    val parsed = sm.ShardRegionStats.parseFrom(bytes)
    val stats: Map[String, Int] = parsed.getStatsList.asScala.iterator.map(e => e.getKey -> e.getValue).toMap
    ShardRegionStats(stats)
  }

  private def startEntityToByteArray(s: StartEntity): Array[Byte] = {
    val builder = sm.StartEntity.newBuilder()
    builder.setEntityId(s.entityId)
    builder.build().toByteArray
  }

  private def startEntityFromBinary(bytes: Array[Byte]): StartEntity = {
    val se = sm.StartEntity.parseFrom(bytes)
    StartEntity(se.getEntityId)
  }

  private def startEntityAckToByteArray(s: StartEntityAck): Array[Byte] = {
    val builder = sm.StartEntityAck.newBuilder()
    builder.setEntityId(s.entityId)
    builder.setShardId(s.shardId)
    builder.build().toByteArray
  }

  private def startEntityAckFromBinary(bytes: Array[Byte]): StartEntityAck = {
    val sea = sm.StartEntityAck.parseFrom(bytes)
    StartEntityAck(sea.getEntityId, sea.getShardId)
  }

  private def resolveActorRef(path: String): ActorRef = {
    system.provider.resolveActorRef(path)
  }

  private def compress(msg: MessageLite): Array[Byte] = {
    val bos = new ByteArrayOutputStream(BufferSize)
    val zip = new GZIPOutputStream(bos)
    try msg.writeTo(zip)
    finally zip.close()
    bos.toByteArray
  }

  private def decompress(bytes: Array[Byte]): Array[Byte] = {
    val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
    val out = new ByteArrayOutputStream()
    val buffer = new Array[Byte](BufferSize)

    @tailrec def readChunk(): Unit = in.read(buffer) match {
      case -1 => ()
      case n =>
        out.write(buffer, 0, n)
        readChunk()
    }

    try readChunk()
    finally in.close()
    out.toByteArray
  }

}
