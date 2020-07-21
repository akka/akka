/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.{ Map => JMap }

import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.cluster.sharding.typed.ActiveActiveShardingExtension
import akka.cluster.sharding.typed.ActiveActiveSharding
import akka.cluster.sharding.typed.ActiveActiveShardingSettings
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId
import org.slf4j.LoggerFactory
import akka.actor.typed.scaladsl.LoggerOps
import akka.cluster.sharding.typed.ActiveActiveShardingDirectReplication

import scala.collection.JavaConverters._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ActiveActiveShardingExtensionImpl(system: ActorSystem[_])
    extends ActiveActiveShardingExtension {

  private val counter = new AtomicLong(0)

  private val logger = LoggerFactory.getLogger(getClass)

  override def init[M, E](settings: ActiveActiveShardingSettings[M, E]): ActiveActiveSharding[M] = {
    val sharding = ClusterSharding(system)
    val initializedReplicas = settings.replicas.map { replicaSettings =>
      // start up a sharding instance per replica id
      logger.infoN(
        "Starting Active Active sharding for replica [{}] (ShardType: [{}])",
        replicaSettings.replicaId.id,
        replicaSettings.entity.typeKey.name)
      val regionOrProxy = sharding.init(replicaSettings.entity)
      (replicaSettings.replicaId, replicaSettings.entity.typeKey, regionOrProxy)
    }

    if (settings.directReplication) {
      logger.infoN("Starting Active Active Direct Replication")
      val replicaToRegionOrProxy = initializedReplicas.map {
        case (id, _, regionOrProxy) => id -> regionOrProxy
      }.toMap
      system.systemActorOf(
        ActiveActiveShardingDirectReplication(replicaToRegionOrProxy),
        s"activeActiveDirectReplication-${counter.incrementAndGet()}")
    }

    val replicaToTypeKey = initializedReplicas.map { case (id, typeKey, _) => id -> typeKey }.toMap
    new ActiveActiveShardingImpl(sharding, replicaToTypeKey)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ActiveActiveShardingImpl[M](
    sharding: ClusterSharding,
    replicaTypeKeys: Map[ReplicaId, EntityTypeKey[M]])
    extends ActiveActiveSharding[M] {

  override def entityRefsFor(entityId: String): Map[ReplicaId, EntityRef[M]] =
    replicaTypeKeys.map {
      case (replicaId, typeKey) =>
        replicaId -> sharding.entityRefFor(typeKey, PersistenceId.ofUniqueId(entityId).id)
    }

  override def getEntityRefsFor(entityId: String): JMap[ReplicaId, EntityRef[M]] =
    entityRefsFor(entityId).asJava

}
