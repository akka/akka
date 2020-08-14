/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.{ Map => JMap }

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.cluster.sharding.typed.ReplicatedShardingExtension
import akka.cluster.sharding.typed.ReplicatedSharding
import akka.cluster.sharding.typed.ReplicatedEntityProvider
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.ReplicaId
import org.slf4j.LoggerFactory
import akka.actor.typed.scaladsl.LoggerOps
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.sharding.typed.ShardingDirectReplication
import akka.persistence.typed.ReplicationId
import akka.util.ccompat.JavaConverters._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ReplicatedShardingExtensionImpl(system: ActorSystem[_]) extends ReplicatedShardingExtension {

  private val counter = new AtomicLong(0)

  private val logger = LoggerFactory.getLogger(getClass)

  override def init[M, E](settings: ReplicatedEntityProvider[M, E]): ReplicatedSharding[M, E] =
    initInternal(None, settings)

  override def init[M, E](thisReplica: ReplicaId, settings: ReplicatedEntityProvider[M, E]): ReplicatedSharding[M, E] =
    initInternal(Some(thisReplica), settings)

  private def initInternal[M, E](
      thisReplica: Option[ReplicaId],
      settings: ReplicatedEntityProvider[M, E]): ReplicatedSharding[M, E] = {
    val sharding = ClusterSharding(system)
    val initializedReplicas = settings.replicas.map {
      case (replicaSettings, typeName) =>
        // start up a sharding instance per replica id
        logger.infoN(
          "Starting Replicated Event Sourcing sharding for replica [{}] (ShardType: [{}])",
          replicaSettings.replicaId.id,
          replicaSettings.entity.typeKey.name)
        val regionOrProxy = sharding.init(replicaSettings.entity)
        (
          typeName,
          replicaSettings.replicaId,
          replicaSettings.entity.typeKey,
          regionOrProxy,
          replicaSettings.entity.dataCenter)
    }
    val replicaToRegionOrProxy = initializedReplicas.map {
      case (_, replicaId, _, regionOrProxy, _) => replicaId -> regionOrProxy
    }.toMap
    if (settings.directReplication) {
      logger.infoN("Starting Replicated Event Sourcing Direct Replication")
      system.systemActorOf(
        ShardingDirectReplication(thisReplica, replicaToRegionOrProxy),
        s"directReplication-${counter.incrementAndGet()}")
    }

    val replicaToTypeKey = initializedReplicas.map {
      case (typeName, id, typeKey, _, dc) => id -> ((typeKey, dc, typeName))
    }.toMap
    new ReplicatedShardingImpl(sharding, replicaToRegionOrProxy, replicaToTypeKey)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ReplicatedShardingImpl[M, E](
    sharding: ClusterSharding,
    shardingPerReplica: Map[ReplicaId, ActorRef[E]],
    replicaTypeKeys: Map[ReplicaId, (EntityTypeKey[M], Option[DataCenter], String)])
    extends ReplicatedSharding[M, E] {

  // FIXME add test coverage for these
  override def shardingRefs: Map[ReplicaId, ActorRef[E]] = shardingPerReplica
  override def getShardingRefs: JMap[ReplicaId, ActorRef[E]] = shardingRefs.asJava

  override def entityRefsFor(entityId: String): Map[ReplicaId, EntityRef[M]] =
    replicaTypeKeys.map {
      case (replicaId, (typeKey, dc, typeName)) =>
        replicaId -> (dc match {
          case None => sharding.entityRefFor(typeKey, ReplicationId(typeName, entityId, replicaId).persistenceId.id)
          case Some(dc) =>
            sharding.entityRefFor(typeKey, ReplicationId(typeName, entityId, replicaId).persistenceId.id, dc)
        })
    }

  override def getEntityRefsFor(entityId: String): JMap[ReplicaId, EntityRef[M]] =
    entityRefsFor(entityId).asJava

}
