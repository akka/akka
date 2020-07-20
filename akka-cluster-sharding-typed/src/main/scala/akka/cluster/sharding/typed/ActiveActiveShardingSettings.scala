/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.sharding.typed.javadsl.{Entity => JEntity, EntityTypeKey => JEntityTypeKey}
import akka.persistence.typed.ReplicaId

import scala.collection.immutable
import scala.reflect.ClassTag
import scala.collection.JavaConverters._
import java.util.{Set => JSet}

import akka.annotation.ApiMayChange
import akka.cluster.sharding.typed.internal.EntityTypeKeyImpl

@ApiMayChange
object ActiveActiveShardingSettings {

  /**
   * Java API:
   *
   * @tparam M The type of messages the active active entity accepts
   * @tparam E The type for envelopes used for sending `M`s over sharding
   */
  def create[M, E](
      messageClass: Class[M],
      allReplicaIds: JSet[ReplicaId],
      settingsPerReplicaFactory: akka.japi.function.Function3[
        JEntityTypeKey[M],
        ReplicaId,
        JSet[ReplicaId],
        ActiveActiveShardingReplicaSettings[M, E]]): ActiveActiveShardingSettings[M, E] = {
    implicit val classTag: ClassTag[M] = ClassTag(messageClass)
    apply[M, E](allReplicaIds.asScala.toSet)((key, replica, _) =>
      settingsPerReplicaFactory(key.asInstanceOf[EntityTypeKeyImpl[M]], replica, allReplicaIds))
  }

  /** Scala API
   *
   * @tparam M The type of messages the active active entity accepts
   * @tparam E The type for envelopes used for sending `M`s over sharding
   */
  def apply[M: ClassTag, E](allReplicaIds: Set[ReplicaId])(
      settingsPerReplicaFactory: (
          EntityTypeKey[M],
          ReplicaId,
          Set[ReplicaId]) => ActiveActiveShardingReplicaSettings[M, E])
      : ActiveActiveShardingSettings[M, E] = {
    new ActiveActiveShardingSettings(allReplicaIds.map { replicaId =>
      val typeKey = EntityTypeKey[M](replicaId.id)
      settingsPerReplicaFactory(typeKey, replicaId, allReplicaIds)
    }.toVector)
  }
}

/**
 * @tparam M The type of messages the active active entity accepts
 * @tparam E The type for envelopes used for sending `M`s over sharding
 */
@ApiMayChange
final class ActiveActiveShardingSettings[M, E] private (
    val replicas: immutable.Seq[ActiveActiveShardingReplicaSettings[M, E]])

@ApiMayChange
object ActiveActiveShardingReplicaSettings {

  /** Java API: */
  def create[M, E](replicaId: ReplicaId, entity: JEntity[M, E]): ActiveActiveShardingReplicaSettings[M, E] =
    apply(replicaId, entity.toScala)

  /** Scala API */
  def apply[M, E](replicaId: ReplicaId, entity: Entity[M, E]): ActiveActiveShardingReplicaSettings[M, E] =
    new ActiveActiveShardingReplicaSettings(replicaId, entity)
}

@ApiMayChange
final class ActiveActiveShardingReplicaSettings[M, E] private (val replicaId: ReplicaId, val entity: Entity[M, E])
