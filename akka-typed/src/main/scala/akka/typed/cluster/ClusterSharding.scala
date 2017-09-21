/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ClusterShardingSettings
import akka.typed.{ ActorRef, ActorSystem, Behavior, Extension, ExtensionId, Props }

sealed case class ShardingEnvelope[A](entityId: String, message: A)
object StartEntity {
  def apply[A](entityId: String): ShardingEnvelope[A] =
    new ShardingEnvelope[A](entityId, null.asInstanceOf[A])
}

object TypedMessageExtractor {

  /**
   * Scala API:
   *
   * Create the default message extractor, using envelopes to identify what entity a message is for
   * and the hashcode of the entityId to decide which shard an entity belongs to.
   *
   * This is recommended since it does not force details about sharding into the entity protocol
   */
  def apply[A](maxNumberOfShards: Int): TypedMessageExtractor[ShardingEnvelope[A], A] =
    new DefaultMessageExtractor[A](maxNumberOfShards)

  /**
   * Scala API:
   *
   * Create a message extractor for a protocol where the entity id is available in each message.
   */
  def noEnvelope[A](
    maxNumberOfShards: Int,
    extractEntityId:   A ⇒ String
  ): TypedMessageExtractor[A, A] =
    new DefaultNoEnvelopeMessageExtractor[A](maxNumberOfShards) {
      // TODO catch MatchError here and return null for those to yield an "unhandled" when partial functions are used?
      def entityId(message: A) = extractEntityId(message)
    }

}

/**
 * Entirely customizable typed message extractor. Prefer [[DefaultMessageExtractor]] or [[DefaultNoEnvelopeMessageExtractor]]
 * if possible.
 *
 * @tparam E Possibly an envelope around the messages accepted by the entity actor, is the same as `A` if there is no
 *           envelope.
 * @tparam A The type of message accepted by the entity actor
 */
trait TypedMessageExtractor[E, A] {

  /**
   * Extract the entity id from an incoming `message`. If `null` is returned
   * the message will be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   */
  def entityId(message: E): String

  /**
   * Extract the message to send to the entity from an incoming `message`.
   * Note that the extracted message does not have to be the same as the incoming
   * message to support wrapping in message envelope that is unwrapped before
   * sending to the entity actor.
   *
   * If the returned value is `null`, and the entity isn't running yet the entity will be started
   * but no message will be delivered to it.
   */
  def entityMessage(message: E): A

  /**
   * Extract the entity id from an incoming `message`. Only messages that passed the [[#entityId]]
   * function will be used as input to this function.
   */
  def shardId(message: E): String
}

/**
 * Java API:
 *
 * Default message extractor type, using envelopes to identify what entity a message is for
 * and the hashcode of the entityId to decide which shard an entity belongs to.
 *
 * This is recommended since it does not force details about sharding into the entity protocol
 *
 * @tparam A The type of message accepted by the entity actor
 */
final class DefaultMessageExtractor[A](maxNumberOfShards: Int) extends TypedMessageExtractor[ShardingEnvelope[A], A] {
  def entityId(envelope: ShardingEnvelope[A]) = envelope.entityId
  def entityMessage(envelope: ShardingEnvelope[A]) = envelope.message
  def shardId(envelope: ShardingEnvelope[A]) = (math.abs(envelope.entityId.hashCode) % maxNumberOfShards).toString
}

/**
 * Java API:
 *
 * Default message extractor type, using a property of the message to identify what entity a message is for
 * and the hashcode of the entityId to decide which shard an entity belongs to.
 *
 * This is recommended since it does not force details about sharding into the entity protocol
 *
 * @tparam A The type of message accepted by the entity actor
 */
abstract class DefaultNoEnvelopeMessageExtractor[A](maxNumberOfShards: Int) extends TypedMessageExtractor[A, A] {
  def entityMessage(message: A) = message
  def shardId(message: A) = {
    val id = entityId(message)
    if (id != null) (math.abs(id.hashCode) % maxNumberOfShards).toString
    else null
  }
}

/**
 * A reference to an entityId and the local access to sharding, allows for actor-like interaction
 *
 * The entity ref must be resolved locally and cannot be sent to another node.
 *
 * TODO what about ask, should it actually implement ActorRef to be exactly like ActorRef and callers does not have
 *      to know at all about it or is it good with a distinction but lookalike API?
 */
trait EntityRef[A] {
  /**
   * Send a message to the entity referenced by this EntityRef using *at-most-once*
   * messaging semantics.
   */
  def tell(msg: A): Unit
}

object EntityRef {
  implicit final class EntityRefOps[T](val ref: EntityRef[T]) extends AnyVal {
    /**
     * Send a message to the Actor referenced by this ActorRef using *at-most-once*
     * messaging semantics.
     */
    def !(msg: T): Unit = ref.tell(msg)
  }
}

object ClusterSharding extends ExtensionId[ClusterSharding] {
  def createExtension(system: ActorSystem[_]): ClusterSharding = ???

  /**
   * Create an ActorRef-like reference to a specific sharded entity. Messages sent to it will be wrapped
   * in a [[ShardingEnvelope]] and passed to the local shard region or proxy.
   */
  def entityRefFor[A](entityId: String, actorRef: ActorRef[ShardingEnvelope[A]]): EntityRef[A] =
    new EntityRef[A] {
      def tell(msg: A): Unit = actorRef ! ShardingEnvelope(entityId, msg)
    }

}

trait ClusterSharding extends Extension {

  /**
   * Spawn a shard region or a proxy depending on if the settings require role and if this node has such a role.
   *
   * Messages are sent to the entities by wrapping the messages in a [[ShardingEnvelope]] with the entityId of the
   * recipient actor.
   * A [[DefaultMessageExtractor]] will be used for extracting entityId and shardId
   * [[akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy]] will be used for shard allocation strategy.
   *
   * @param behavior The behavior for entities
   * @param typeName A name that uniquely identifies the type of entity in this cluster
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam A The type of command the entity accepts
   */
  def spawn[A](
    behavior:           Behavior[A],
    typeName:           String,
    settings:           ClusterShardingSettings,
    maxNumberOfShards:  Int,
    handOffStopMessage: A): ActorRef[ShardingEnvelope[A]]

  /**
   * Spawn a shard region or a proxy depending on if the settings require role and if this node has such a role.
   *
   * @param behavior The behavior for entities
   * @param typeName A name that uniquely identifies the type of entity in this cluster
   * @param entityProps Props to apply when starting an entity
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam E A possible envelope around the message the entity accepts
   * @tparam A The type of command the entity accepts
   */
  def spawn[E, A](
    behavior:           Behavior[A],
    typeName:           String,
    entityProps:        Props,
    settings:           ClusterShardingSettings,
    messageExtractor:   TypedMessageExtractor[E, A],
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: A
  ): ActorRef[E]

}
