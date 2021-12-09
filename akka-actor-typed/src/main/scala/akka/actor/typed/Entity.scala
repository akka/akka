/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import scala.reflect.ClassTag

import akka.actor.typed.Entity.EntityCommand
import akka.actor.typed.Entity.EntitySettings
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi

object Entity {

  @DoNotInherit trait EntityCommand

  final case class Passivate[M](entity: ActorRef[M]) extends EntityCommand

  trait EntitySettings
  def apply[M](typeKey: EntityTypeKey[M])(
      createBehavior: EntityContext[M] => Behavior[M]): Entity[M, EntityEnvelope[M]] =
    new Entity(createBehavior, typeKey, None, Props.empty, None, None)
}

final class Entity[M, E] private[akka] (
    val createBehavior: EntityContext[M] => Behavior[M],
    val typeKey: EntityTypeKey[M],
    val stopMessage: Option[M],
    val entityProps: Props,
    val settings: Option[EntitySettings],
    val messageExtractor: Option[EntityMessageExtractor[E, M]]) {

  /**
   * [[akka.actor.typed.Props]] of the entity actors, such as dispatcher settings.
   */
  def withEntityProps(newEntityProps: Props): Entity[M, E] =
    copy(entityProps = newEntityProps)

  /**
   * Additional settings, typically loaded from configuration.
   */
  def withSettings(newSettings: EntitySettings): Entity[M, E] =
    copy(settings = Option(newSettings))

  /**
   * Message sent to an entity to tell it to stop, e.g. when rebalanced or passivated.
   * If this is not defined it will be stopped automatically.
   * It can be useful to define a custom stop message if the entity needs to perform
   * some asynchronous cleanup or interactions before stopping.
   */
  def withStopMessage(newStopMessage: M): Entity[M, E] =
    copy(stopMessage = Option(newStopMessage))

  /**
   * If a `messageExtractor` is not specified the messages are sent to the entities by wrapping
   * them in [[EntityEnvelope]] with the entityId of the recipient actor. That envelope
   * is used by the [[EnvelopeMessageExtractor]] for extracting entityId.
   *
   * If used with cluster sharding, the number of shards is then defined by `numberOfShards` in `ClusterShardingSettings`,
   * which by default is configured with `akka.cluster.sharding.number-of-shards`.
   */
  def withMessageExtractor[Envelope](newExtractor: EntityMessageExtractor[Envelope, M]): Entity[M, Envelope] =
    new Entity(createBehavior, typeKey, stopMessage, entityProps, settings, Option(newExtractor))

  private def copy(
      createBehavior: EntityContext[M] => Behavior[M] = createBehavior,
      typeKey: EntityTypeKey[M] = typeKey,
      stopMessage: Option[M] = stopMessage,
      entityProps: Props = entityProps,
      settings: Option[EntitySettings] = settings): Entity[M, E] = {
    new Entity(createBehavior, typeKey, stopMessage, entityProps, settings, messageExtractor)
  }
}

/**
 * The key of an entity type, the `name` must be unique.
 *
 * Not for user extension.
 */
@DoNotInherit trait EntityTypeKey[-T] {

  /**
   * Name of the entity type.
   */
  def name: String

}

object EntityTypeKey {

  /**
   * Creates an `EntityTypeKey`. The `name` must be unique.
   */
  def apply[T](name: String)(implicit tTag: ClassTag[T]): EntityTypeKey[T] =
    EntityTypeKeyImpl(name, implicitly[ClassTag[T]].runtimeClass.getName)

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class EntityTypeKeyImpl[T](name: String, messageClassName: String)
    extends EntityTypeKey[T] {

  override def toString: String = s"EntityTypeKey[$messageClassName]($name)"

}

final class EntityContext[M](
    val entityTypeKey: EntityTypeKey[M],
    val entityId: String,
    val manager: ActorRef[EntityCommand])
