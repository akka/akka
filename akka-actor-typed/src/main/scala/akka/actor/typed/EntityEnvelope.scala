/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.InvalidMessageException
import akka.actor.WrappedMessage
import akka.util.unused

object EntityEnvelope {

  final case class StartEntity(entityId: String)

  /** Allows starting a specific Entity by its entity identifier */
  object StartEntity {

    /**
     * Returns [[EntityEnvelope]] which can be sent in order to wake up the
     * specified (by `entityId`) Entity, ''without'' delivering a real message to it.
     */
    def apply[M](entityId: String): EntityEnvelope[M] = {
      // StartEntity isn't really of type M, but erased and StartEntity is only handled internally, not delivered to the entity
      new EntityEnvelope[M](entityId, new StartEntity(entityId).asInstanceOf[M])
    }
  }
}
final case class EntityEnvelope[M](entityId: String, message: M) extends WrappedMessage {
  if (message == null) throw InvalidMessageException("[null] is not an allowed message")
}

object EntityMessageExtractor {

  /**
   * Scala API:
   *
   * Create the default message extractor, using envelopes to identify what entity a message is for.
   */
  def apply[M](numberOfShards: Int): EntityMessageExtractor[EntityEnvelope[M], M] =
    new EnvelopeMessageExtractor[M](numberOfShards)

  /**
   * Scala API: Create a message extractor for a protocol where the entity id is available in each message.
   */
  def noEnvelope[M](@unused stopMessage: M)(extractEntityId: M => String): EntityMessageExtractor[M, M] =
    new NoEnvelopeMessageExtractor[M]() {
      def entityId(message: M): String = extractEntityId(message)
    }
}
trait EntityMessageExtractor[E, M] {

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
   */
  def unwrapMessage(message: E): M
}

/**
 * Default message extractor type, using envelopes to identify what entity a message is for.
 *
 * @tparam M The type of message accepted by the entity actor
 */
final class EnvelopeMessageExtractor[M](val numberOfShards: Int) extends EntityMessageExtractor[EntityEnvelope[M], M] {

  override def entityId(envelope: EntityEnvelope[M]): String = envelope.entityId

  override def unwrapMessage(envelope: EntityEnvelope[M]): M = envelope.message
}

/**
 * Default message extractor type, using a property of the message to identify what entity a message is for.
 *
 * @tparam M The type of message accepted by the entity actor
 */
abstract class NoEnvelopeMessageExtractor[M]() extends EntityMessageExtractor[M, M] {

  override final def unwrapMessage(message: M): M = message

  override def toString = s"NoEnvelopeMessageExtractor()"
}
