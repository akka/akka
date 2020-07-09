package akka.cluster.sharding.typed.scaladsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.cluster.sharding.typed.scaladsl
import akka.persistence.typed.ReplicaId

import scala.collection.immutable

// FIXME needs to be better structure for bin.comp. in the end
object ActiveActiveShardingSettings {
  sealed trait MessagingStrategy
  case object Random extends MessagingStrategy // not sure any other makes sense, since you probably won't use the same entity ref
  case object TailChop extends MessagingStrategy // not sure we can implement this? we'd need to require and to wrap Ask because we can't tail chop without knowing about the reply

  def apply[M, E](replicaId: Set[ReplicaId], messagingStrategy: MessagingStrategy)(
      entityFactory: (EntityTypeKey[M], ReplicaId, Set[ReplicaId]) => ActiveActiveShardingReplicaSettings[M, E])
      : ActiveActiveShardingSettings[M, E] = ???
}

case class ActiveActiveShardingSettings[M, E](
    replicas: immutable.Seq[ActiveActiveShardingReplicaSettings[M, E]],
    messagingStrategy: ActiveActiveShardingSettings.MessagingStrategy)
case class ActiveActiveShardingReplicaSettings[M, E](replicaId: ReplicaId, entity: Entity[M, E])

object ActiveActiveSharding extends ExtensionId[ActiveActiveSharding] {

  override def createExtension(system: ActorSystem[_]): ActiveActiveSharding = new ActiveActiveShardingImpl

}

trait ActiveActiveSharding extends Extension {
  def init[M, E](settings: ActiveActiveShardingSettings[M, E]): ActiveActiveShardingInstance[M, E]
}

// FIXME naming is hard
trait ActiveActiveShardingInstance[M, E] {

  def entityRefsFor(entityId: String): Set[EntityRef[M]]

  /**
   * Chose a replica randomly for each message being sent to the EntityRef.
   */
  def randomRefFor(entityId: String): EntityRef[M]

  // FIXME ideally we'd want some different clever strategies here but that is cut out of scope for now
}

// FIXME move to internals once done sketching out
private[akka] class ActiveActiveShardingImpl extends ActiveActiveSharding {
  override def init[M, E](settings: ActiveActiveShardingSettings[M, E]): ActiveActiveShardingInstance[M, E] = {
    // keep a registry keyed by the settings
    // start up a sharding instance per replica id
    ???
  }
}

private[akka] class ActiveActiveShardingInstanceImpl[M, E] extends ActiveActiveShardingInstance[M, E] {
  // FIXME should this be the only path to message the actors or should we also provide access to the replicaId -> shardregion
  // somehow for ultimate flexibility?

  override def entityRefFor(entityId: String): EntityRef[M] = {
    // create something like an EntityRef that routes over the sharding instances with the given strategy
    ???
  }
}
