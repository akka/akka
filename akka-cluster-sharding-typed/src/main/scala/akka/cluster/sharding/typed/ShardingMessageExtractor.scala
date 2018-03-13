/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

object ShardingMessageExtractor {

  /**
   * Scala API:
   *
   * Create the default message extractor, using envelopes to identify what entity a message is for
   * and the hashcode of the entityId to decide which shard an entity belongs to.
   *
   * This is recommended since it does not force details about sharding into the entity protocol
   */
  def apply[A](maxNumberOfShards: Int, handOffStopMessage: A): ShardingMessageExtractor[ShardingEnvelope[A], A] =
    new HashCodeMessageExtractor[A](maxNumberOfShards, handOffStopMessage)

  /**
   * Scala API: Create a message extractor for a protocol where the entity id is available in each message.
   */
  def noEnvelope[A](
    maxNumberOfShards:  Int,
    handOffStopMessage: A)(
    extractEntityId: A ⇒ String): ShardingMessageExtractor[A, A] =
    new HashCodeNoEnvelopeMessageExtractor[A](maxNumberOfShards, handOffStopMessage) {
      def entityId(message: A) = extractEntityId(message)
    }

}

/**
 * Entirely customizable typed message extractor. Prefer [[HashCodeMessageExtractor]] or
 * [[HashCodeNoEnvelopeMessageExtractor]]if possible.
 *
 * @tparam E Possibly an Envelope around the messages accepted by the entity actor, is the same as `A` if there is no
 *           envelope.
 * @tparam A The type of message accepted by the entity actor
 */
abstract class ShardingMessageExtractor[E, A] {

  /**
   * Extract the entity id from an incoming `message`. If `null` is returned
   * the message will be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   */
  def entityId(message: E): String

  /**
   * The shard identifier for a given entity id. Only messages that passed the [[ShardingMessageExtractor#entityId]]
   * function will be used as input to this function.
   */
  def shardId(entityId: String): String

  /**
   * Extract the message to send to the entity from an incoming `message`.
   * Note that the extracted message does not have to be the same as the incoming
   * message to support wrapping in message envelope that is unwrapped before
   * sending to the entity actor.
   */
  def unwrapMessage(message: E): A

  /**
   * Message sent to an entity to tell it to stop, e.g. when rebalanced.
   * The message defined here is not passed to `entityId`, `shardId` or `unwrapMessage`.
   */
  def handOffStopMessage: A
}

/**
 * Default message extractor type, using envelopes to identify what entity a message is for
 * and the hashcode of the entityId to decide which shard an entity belongs to.
 *
 * This is recommended since it does not force details about sharding into the entity protocol
 *
 * @tparam A The type of message accepted by the entity actor
 */
final class HashCodeMessageExtractor[A](
  val maxNumberOfShards:           Int,
  override val handOffStopMessage: A)
  extends ShardingMessageExtractor[ShardingEnvelope[A], A] {

  override def entityId(envelope: ShardingEnvelope[A]): String = envelope.entityId
  override def shardId(entityId: String): String = (math.abs(entityId.hashCode) % maxNumberOfShards).toString
  override def unwrapMessage(envelope: ShardingEnvelope[A]): A = envelope.message
}

/**
 * Default message extractor type, using a property of the message to identify what entity a message is for
 * and the hashcode of the entityId to decide which shard an entity belongs to.
 *
 * This is recommended since it does not force details about sharding into the entity protocol
 *
 * @tparam A The type of message accepted by the entity actor
 */
abstract class HashCodeNoEnvelopeMessageExtractor[A](
  val maxNumberOfShards:           Int,
  override val handOffStopMessage: A)
  extends ShardingMessageExtractor[A, A] {

  override def shardId(entityId: String): String = (math.abs(entityId.hashCode) % maxNumberOfShards).toString
  override final def unwrapMessage(message: A): A = message

  override def toString = s"HashCodeNoEnvelopeMessageExtractor($maxNumberOfShards)"
}

/**
 * Default envelope type that may be used with Cluster Sharding.
 *
 * Cluster Sharding provides a default [[HashCodeMessageExtractor]] that is able to handle
 * these types of messages, by hashing the entityId into into the shardId. It is not the only,
 * but a convenient way to send envelope-wrapped messages via cluster sharding.
 *
 * The alternative way of routing messages through sharding is to not use envelopes,
 * and have the message types themselves carry identifiers.
 */
final case class ShardingEnvelope[A](entityId: String, message: A) // TODO think if should remain a case class

