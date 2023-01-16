/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.entity

import scala.collection.immutable

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Entity
import akka.actor.typed.EntityRef
import akka.actor.typed.EntityTypeKey
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi

object EntityExtension extends ExtensionId[EntityExtension] {

  /**
   * Create the extension, will be invoked at most one time per actor system where the extension is registered.
   */
  override def createExtension(system: ActorSystem[_]): EntityExtension =
    new EntityExtensionImpl(system)

  def get(system: ActorSystem[_]): EntityExtension = apply(system)

}

@DoNotInherit
abstract class EntityExtension extends Extension {
  def initEntity[M, E](entity: Entity[M, E]): ActorRef[E]
  def entityRefFor[M](typeKey: EntityTypeKey[M], entityId: String): EntityRef[M]
}

@InternalApi
private[akka] class EntityExtensionImpl(system: ActorSystem[_]) extends EntityExtension {

  val provider: EntityProvider =
    if (system.settings.classicSettings.ProviderSelectionType.hasCluster) {
      system.dynamicAccess
        .createInstanceFor[EntityProvider](
          "akka.cluster.sharding.typed.internal.ClusterShardingEntityProvider",
          immutable.Seq((classOf[ActorSystem[_]], system)))
        .recover {
          case e =>
            throw new RuntimeException(
              "ShardedEntityProvider could not be loaded dynamically. Make sure you have " +
              "'akka-cluster-typed' in the classpath.",
              e)
        }
        .get
    } else {
      new LocalEntityProvider(system)
    }

  override def initEntity[M, E](entity: Entity[M, E]): ActorRef[E] =
    provider.initEntity(entity)

  override def entityRefFor[M](typeKey: EntityTypeKey[M], entityId: String): EntityRef[M] =
    provider.entityRefFor(typeKey, entityId)

}
