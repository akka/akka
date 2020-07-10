package akka.cluster.sharding.typed.internal

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

import scala.util.Random

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ActiveActiveShardingExtensionImpl(system: ActorSystem[_]) extends ActiveActiveShardingExtension {
  override def init[M, E](settings: ActiveActiveShardingSettings[M, E]): ActiveActiveSharding[M, E] = {
    val sharding = ClusterSharding(system)
    val replicaTypeKeys = settings.replicas.map { replicaSettings =>
      // start up a sharding instance per replica id
      sharding.init(replicaSettings.entity)
      (replicaSettings.replicaId, replicaSettings.entity.typeKey)
    }.toMap

    new ActiveActiveShardingImpl(sharding, replicaTypeKeys)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ActiveActiveShardingImpl[M, E](
                                                            sharding: ClusterSharding,
                                                            replicaTypeKeys: Map[ReplicaId, EntityTypeKey[M]])
  extends ActiveActiveSharding[M, E] {

  override def entityRefsFor(entityId: String): Map[ReplicaId, EntityRef[M]] =
    replicaTypeKeys.map {
      case (replicaId, typeKey) =>
        replicaId -> sharding.entityRefFor(typeKey, PersistenceId.replicatedUniqueId(entityId, replicaId).id)
    }

  override def randomRefFor(entityId: String): EntityRef[M] =
    Random.shuffle(entityRefsFor(entityId).values).head

}
