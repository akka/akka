package akka.cluster.sharding.typed

import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.ReplicaId

import scala.collection.immutable
import scala.reflect.ClassTag
import scala.collection.JavaConverters._
import java.util.{List => JList}

object ActiveActiveShardingSettings {

  /**
   * Java API:
   *
   * @tparam M The type of messages the active active entity accepts
   * @tparam E The type for envelopes used for sending `M`s over sharding
   */
  def create[M, E](
                    messageClass: Class[M],
                    allReplicaIds: JList[ReplicaId],
                    settingsPerReplicaFactory: akka.japi.function.Function3[EntityTypeKey[M], ReplicaId, JList[ReplicaId], ActiveActiveShardingReplicaSettings[M, E]]
                  ): ActiveActiveShardingSettings[M, E] = {
    implicit val classTag = ClassTag(messageClass)
    apply[M, E](allReplicaIds.asScala.toVector)((key, replica, _) => settingsPerReplicaFactory(key, replica, allReplicaIds))
  }

  /** Scala API
   *
   * @tparam M The type of messages the active active entity accepts
   * @tparam E The type for envelopes used for sending `M`s over sharding
   */
  def apply[M : ClassTag, E](allReplicaIds: immutable.Seq[ReplicaId])(
    settingsPerReplicaFactory: (
        EntityTypeKey[M],
        ReplicaId,
        immutable.Seq[ReplicaId]) => ActiveActiveShardingReplicaSettings[M, E]): ActiveActiveShardingSettings[M, E] = {
    new ActiveActiveShardingSettings(allReplicaIds.map { replicaId =>
      val typeKey = EntityTypeKey[M](replicaId.id)
      settingsPerReplicaFactory(typeKey, replicaId, allReplicaIds)
    })
  }
}


/**
 * @tparam M The type of messages the active active entity accepts
 * @tparam E The type for envelopes used for sending `M`s over sharding
 */
final class ActiveActiveShardingSettings[M, E] private (val replicas: immutable.Seq[ActiveActiveShardingReplicaSettings[M, E]])

object ActiveActiveShardingReplicaSettings {
  /** Java API: */
  def create[M, E](replicaId: ReplicaId, entity: Entity[M, E]): ActiveActiveShardingReplicaSettings[M ,E] = apply(replicaId, entity)

  /** Scala API */
  def apply[M, E](replicaId: ReplicaId, entity: Entity[M, E]): ActiveActiveShardingReplicaSettings[M ,E] =
    new ActiveActiveShardingReplicaSettings(replicaId, entity)
}

final class ActiveActiveShardingReplicaSettings[M, E] private (val replicaId: ReplicaId, val entity: Entity[M, E])

