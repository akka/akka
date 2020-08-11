/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.sharding.typed.javadsl.{ Entity => JEntity, EntityTypeKey => JEntityTypeKey }
import akka.persistence.typed.ReplicaId

import scala.collection.immutable
import scala.reflect.ClassTag
import akka.util.ccompat.JavaConverters._
import java.util.{ Set => JSet }

import akka.annotation.ApiMayChange
import akka.cluster.sharding.typed.internal.EntityTypeKeyImpl

@ApiMayChange
object ReplicatedEntityProvider {

  /**
   * Java API:
   *
   * @tparam M The type of messages the replicated entity accepts
   * @tparam E The type for envelopes used for sending `M`s over sharding
   */
  def create[M, E](
      messageClass: Class[M],
      typeName: String,
      allReplicaIds: JSet[ReplicaId],
      settingsPerReplicaFactory: akka.japi.function.Function3[
        JEntityTypeKey[M],
        ReplicaId,
        JSet[ReplicaId],
        ReplicatedEntity[M, E]]): ReplicatedEntityProvider[M, E] = {
    implicit val classTag: ClassTag[M] = ClassTag(messageClass)
    apply[M, E](typeName, allReplicaIds.asScala.toSet)((key, replica, _) =>
      settingsPerReplicaFactory(key.asInstanceOf[EntityTypeKeyImpl[M]], replica, allReplicaIds))
  }

  /**
   * Scala API:
   * @param typeName The type name used in the [[EntityTypeKey]]
   * @tparam M The type of messages the replicated entity accepts
   * @tparam E The type for envelopes used for sending `M`s over sharding
   */
  def apply[M: ClassTag, E](typeName: String, allReplicaIds: Set[ReplicaId])(
      settingsPerReplicaFactory: (EntityTypeKey[M], ReplicaId, Set[ReplicaId]) => ReplicatedEntity[M, E])
      : ReplicatedEntityProvider[M, E] = {
    new ReplicatedEntityProvider(allReplicaIds.map { replicaId =>
      // TODO validate it does't contain the separator
      val typeKey = EntityTypeKey[M](s"$typeName|${replicaId.id}")
      (settingsPerReplicaFactory(typeKey, replicaId, allReplicaIds), typeName)
    }.toVector, directReplication = false)
  }
}

/**
 * @tparam M The type of messages the replicated entity accepts
 * @tparam E The type for envelopes used for sending `M`s over sharding
 */
@ApiMayChange
final class ReplicatedEntityProvider[M, E] private (
    val replicas: immutable.Seq[(ReplicatedEntity[M, E], String)],
    val directReplication: Boolean) {

  /**
   * Start direct replication over sharding when replicated sharding starts up, requires the entities
   * to also have it enabled through [[akka.persistence.typed.scaladsl.EventSourcedBehavior.withEventPublishing]]
   * or [[akka.persistence.typed.javadsl.ReplicatedEventSourcedBehavior.withEventPublishing]]
   * to work.
   *
   */
  def withDirectReplication(enabled: Boolean): ReplicatedEntityProvider[M, E] =
    new ReplicatedEntityProvider(replicas, directReplication = enabled)

}

@ApiMayChange
object ReplicatedEntity {

  /**
   * Java API: Defines the [[akka.cluster.sharding.typed.javadsl.Entity]] to use for a given replica, note that the behavior
   * can be a [[akka.persistence.typed.javadsl.ReplicatedEventSourcedBehavior]] or an arbitrary non persistent
   * [[akka.actor.typed.Behavior]] but must never be a regular [[akka.persistence.typed.javadsl.EventSourcedBehavior]]
   * as that requires a single writer and that would cause it to have multiple writers.
   */
  def create[M, E](replicaId: ReplicaId, entity: JEntity[M, E]): ReplicatedEntity[M, E] =
    apply(replicaId, entity.toScala)

  /**
   * Scala API: Defines the [[akka.cluster.sharding.typed.scaladsl.Entity]] to use for a given replica, note that the behavior
   * can be a behavior created with [[akka.persistence.typed.scaladsl.ReplicatedEventSourcing]] or an arbitrary non persistent
   * [[akka.actor.typed.Behavior]] but must never be a regular [[akka.persistence.typed.scaladsl.EventSourcedBehavior]]
   * as that requires a single writer and that would cause it to have multiple writers.
   */
  def apply[M, E](replicaId: ReplicaId, entity: Entity[M, E]): ReplicatedEntity[M, E] =
    new ReplicatedEntity(replicaId, entity)
}

/**
 * Settings for a specific replica id in replicated sharding
 */
@ApiMayChange
final class ReplicatedEntity[M, E] private (val replicaId: ReplicaId, val entity: Entity[M, E])
