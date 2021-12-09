/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import java.net.URLEncoder

import scala.concurrent.Future

import akka.actor.ActorRefProvider
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Entity
import akka.actor.typed.Entity.EntityCommand
import akka.actor.typed.EntityContext
import akka.actor.typed.EntityEnvelope
import akka.actor.typed.EntityRef
import akka.actor.typed.EntityTypeKey
import akka.actor.typed.internal.InternalRecipientRef
import akka.actor.typed.internal.entity.EntityProvider
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor
import akka.cluster.sharding.ShardRegion.{ StartEntity => ClassicStartEntity }
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.ShardingMessageExtractor
import akka.cluster.sharding.typed.internal.{ EntityTypeKeyImpl => ShardedEntityTypeKeyImpl }
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.{ Entity => ShardedEntity }
import akka.cluster.sharding.typed.scaladsl.{ EntityContext => ShardedEntityContext }
import akka.cluster.sharding.typed.scaladsl.{ EntityRef => ShardedEntityRef }
import akka.cluster.sharding.typed.scaladsl.{ EntityTypeKey => ShardedEntityTypeKey }
import akka.pattern.StatusReply
import akka.util.ByteString
import akka.util.Timeout

class ClusterShardingEntityProvider(system: ActorSystem[_]) extends EntityProvider {

  import EntityAdapter._

  private val sharding = ClusterSharding(system)

  override def initEntity[M, E](entity: Entity[M, E]): ActorRef[E] = {

    // TODO: convert EntitySettings to ClusterShardingSettings
    val clusterSettings = entity.settings.map(_ => ClusterShardingSettings(system))

    val shardingMessageExtractor = entity.messageExtractor.map {
      case shardingExtractor: ShardingMessageExtractor[E, M] @unchecked => shardingExtractor
      case messageExtractor                                             =>
        // fall back to number of shards defined in settings
        val numberOfShards = ClusterShardingSettings(system).numberOfShards
        new ShardingMessageExtractor[E, M] {
          override def entityId(message: E): String = messageExtractor.entityId(message)
          override def shardId(entityId: String): String = HashCodeMessageExtractor.shardId(entityId, numberOfShards)
          override def unwrapMessage(message: E): M = messageExtractor.unwrapMessage(message)
        }
    }

    val shardedEntity: ShardedEntity[M, E] =
      new ShardedEntity(
        ctx => entity.createBehavior(toEntityContext(ctx)),
        toShardedEntityTypeKey(entity.typeKey),
        entity.stopMessage,
        entity.entityProps,
        clusterSettings,
        shardingMessageExtractor,
        None,
        None,
        None)

    sharding.init(shardedEntity)
  }

  override def entityRefFor[M](typeKey: EntityTypeKey[M], entityId: String): EntityRef[M] =
    toEntityRef(sharding.entityRefFor(toShardedEntityTypeKey(typeKey), entityId))

}

private[akka] object EntityAdapter {

  def toShardedEntityTypeKey[M](typeKey: EntityTypeKey[M]): ShardedEntityTypeKey[M] =
    typeKey match {
      case shardedTypeKey: ShardedEntityTypeKey[M] @unchecked => shardedTypeKey
      case typeKeyImpl: akka.actor.typed.EntityTypeKeyImpl[M] @unchecked =>
        ShardedEntityTypeKeyImpl(typeKey.name, typeKeyImpl.messageClassName)

      case _ =>
        // won't happen, but need it to make compiler happy
        throw new IllegalArgumentException(s"Unknown entity type key [$typeKey]")
    }

  def toEntityTypeKey[M](typeKey: ShardedEntityTypeKey[M]): EntityTypeKey[M] = {
    val impl = typeKey.asInstanceOf[ShardedEntityTypeKeyImpl[M]]
    akka.actor.typed.EntityTypeKeyImpl[M](typeKey.name, impl.messageClassName)
  }

  def toEntityContext[M](ctx: ShardedEntityContext[M]): EntityContext[M] = {
    val typeKey = toEntityTypeKey(ctx.entityTypeKey)
    // FIXME: needs proper conversion
    val managerRef: ActorRef[EntityCommand] = ctx.shard.asInstanceOf[ActorRef[EntityCommand]]
    new EntityContext(typeKey, ctx.entityId, managerRef)
  }

  def toEntityRef[M](shardedEntity: ShardedEntityRef[M]): EntityRef[M] = new ShardedEntityRefWrapper(shardedEntity)

  private[akka] class ShardedEntityRefWrapper[-M](delegate: ShardedEntityRef[M])
      extends EntityRef[M]
      with InternalRecipientRef[M] {

    override def entityId: String = delegate.entityId

    override def typeKey: EntityTypeKey[M] = toEntityTypeKey(delegate.typeKey)

    private def convertMessage[Msg](msg: Msg): Msg = {
      val convertedMsg =
        msg match {
          case EntityEnvelope.StartEntity(entityId) => ClassicStartEntity(entityId)
          case entityEnv: EntityEnvelope[_]         => ShardingEnvelope(entityEnv.entityId, entityEnv.message)
          case _                                    => msg
        }
      convertedMsg.asInstanceOf[Msg]
    }

    override def tell(msg: M): Unit =
      delegate.tell(convertMessage(msg))

    override def ask[Res](f: ActorRef[Res] => M)(implicit timeout: Timeout): Future[Res] =
      delegate.ask { (ar: ActorRef[Res]) =>
        convertMessage(f(ar))
      }(timeout)

    override def askWithStatus[Res](f: ActorRef[StatusReply[Res]] => M)(implicit timeout: Timeout): Future[Res] =
      delegate.askWithStatus { (ar: ActorRef[StatusReply[Res]]) =>
        convertMessage(f(ar))
      }(timeout)

    override def provider: ActorRefProvider =
      delegate.asInstanceOf[InternalRecipientRef[M]].provider

    override def isTerminated: Boolean =
      delegate.asInstanceOf[InternalRecipientRef[M]].isTerminated

    override def refPrefix: String =
      URLEncoder.encode(s"${typeKey.name}-$entityId", ByteString.UTF_8)

    override def toString: String = delegate.toString
    override def hashCode(): Int = delegate.hashCode()
    override def equals(obj: Any): Boolean = delegate.equals(obj)
  }
}
