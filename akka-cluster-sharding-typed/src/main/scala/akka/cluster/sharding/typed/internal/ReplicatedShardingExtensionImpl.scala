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
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId
import org.slf4j.LoggerFactory
import akka.actor.typed.scaladsl.LoggerOps
import akka.cluster.sharding.typed.ShardingDirectReplication

import akka.util.ccompat.JavaConverters._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ReplicatedShardingExtensionImpl(system: ActorSystem[_]) extends ReplicatedShardingExtension {

  private val counter = new AtomicLong(0)

  private val logger = LoggerFactory.getLogger(getClass)

  override def init[M, E](settings: ReplicatedEntityProvider[M, E]): ReplicatedSharding[M, E] = {
    val sharding = ClusterSharding(system)
    val initializedReplicas = settings.replicas.map { replicaSettings =>
      // start up a sharding instance per replica id
      logger.infoN(
        "Starting Replicated Event Sourcing sharding for replica [{}] (ShardType: [{}])",
        replicaSettings.replicaId.id,
        replicaSettings.entity.typeKey.name)
      val regionOrProxy = sharding.init(replicaSettings.entity)
      (replicaSettings.replicaId, replicaSettings.entity.typeKey, regionOrProxy)
    }
    val replicaToRegionOrProxy = initializedReplicas.map {
      case (id, _, regionOrProxy) => id -> regionOrProxy
    }.toMap
    if (settings.directReplication) {
      logger.infoN("Starting Replicated Event Sourcing Direct Replication")
      system.systemActorOf(
        ShardingDirectReplication(replicaToRegionOrProxy),
        s"directReplication-${counter.incrementAndGet()}")
    }

    val replicaToTypeKey = initializedReplicas.map { case (id, typeKey, _) => id -> typeKey }.toMap
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
    replicaTypeKeys: Map[ReplicaId, EntityTypeKey[M]])
    extends ReplicatedSharding[M, E] {

  override def shardingRefs: Map[ReplicaId, ActorRef[E]] = shardingPerReplica
  override def getShardingRefs: JMap[ReplicaId, ActorRef[E]] = shardingRefs.asJava

  override def entityRefsFor(entityId: String): Map[ReplicaId, EntityRef[M]] =
    replicaTypeKeys.map {
      case (replicaId, typeKey) =>
        replicaId -> sharding.entityRefFor(typeKey, PersistenceId.ofUniqueId(entityId).id)
    }

  override def getEntityRefsFor(entityId: String): JMap[ReplicaId, EntityRef[M]] =
    entityRefsFor(entityId).asJava

}
