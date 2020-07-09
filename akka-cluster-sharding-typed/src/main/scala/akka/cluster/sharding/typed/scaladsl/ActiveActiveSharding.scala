package akka.cluster.sharding.typed.scaladsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId

import scala.collection.immutable
import scala.util.Random

// FIXME needs to be better structure for bin.comp. in the end
object ActiveActiveShardingSettings {

  def apply[M, E](allReplicaIds: Set[ReplicaId])(
      settingsPerReplicaFactory: (
          EntityTypeKey[M],
          ReplicaId,
          Set[ReplicaId]) => ActiveActiveShardingReplicaSettings[M, E]): ActiveActiveShardingSettings[M, E] = ???
}

case class ActiveActiveShardingSettings[M, E](replicas: immutable.Seq[ActiveActiveShardingReplicaSettings[M, E]])
case class ActiveActiveShardingReplicaSettings[M, E](replicaId: ReplicaId, entity: Entity[M, E])

object ActiveActiveSharding extends ExtensionId[ActiveActiveSharding] {

  override def createExtension(system: ActorSystem[_]): ActiveActiveSharding = new ActiveActiveShardingImpl(system)

}

trait ActiveActiveSharding extends Extension {
  def init[M, E](settings: ActiveActiveShardingSettings[M, E]): ActiveActiveShardingInstance[M, E]
}

// FIXME naming is hard
trait ActiveActiveShardingInstance[M, E] {

  def entityRefsFor(entityId: String): Map[ReplicaId, EntityRef[M]]

  /**
   * Chose a replica randomly for each message being sent to the EntityRef.
   */
  def randomRefFor(entityId: String): EntityRef[M]

  // FIXME ideally we'd want some different clever strategies here but that is cut out of scope for now
}

// FIXME move to internals once done sketching out
private[akka] class ActiveActiveShardingImpl(system: ActorSystem[_]) extends ActiveActiveSharding {
  override def init[M, E](settings: ActiveActiveShardingSettings[M, E]): ActiveActiveShardingInstance[M, E] = {
    // FIXME do we need to keep a registry keyed by the settings like in sharding?
    val sharding = ClusterSharding(system)
    val replicaTypeKeys = settings.replicas.map { replicaSettings =>
      // start up a sharding instance per replica id
      sharding.init(replicaSettings.entity)
      (replicaSettings.replicaId, replicaSettings.entity.typeKey)
    }.toMap

    new ActiveActiveShardingInstanceImpl(sharding, replicaTypeKeys)
  }
}

private[akka] class ActiveActiveShardingInstanceImpl[M, E](
    sharding: ClusterSharding,
    replicaTypeKeys: Map[ReplicaId, EntityTypeKey[M]])
    extends ActiveActiveShardingInstance[M, E] {

  override def entityRefsFor(entityId: String): Map[ReplicaId, EntityRef[M]] =
    replicaTypeKeys.map {
      case (replicaId, typeKey) =>
        replicaId -> sharding.entityRefFor(typeKey, PersistenceId.replicatedUniqueId(entityId, replicaId).id)
    }

  override def randomRefFor(entityId: String): EntityRef[M] =
    Random.shuffle(entityRefsFor(entityId).values).head

}
