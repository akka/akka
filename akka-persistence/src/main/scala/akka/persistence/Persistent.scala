/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor.ActorRef

/**
 * Persistent message.
 */
sealed abstract class Persistent {
  /**
   * This persistent message's payload.
   */
  //#payload
  def payload: Any
  //#payload

  /**
   * This persistent message's sequence number.
   */
  //#sequence-nr
  def sequenceNr: Long
  //#sequence-nr

  /**
   * Creates a new persistent message with the specified `payload`.
   */
  def withPayload(payload: Any): Persistent

  /**
   * Called by [[Channel]] destinations to confirm the receipt of a persistent message.
   */
  def confirm(): Unit
}

object Persistent {
  /**
   * Java API.
   *
   * Creates a new persistent message. Must only be used outside processors.
   *
   * @param payload payload of new persistent message.
   */
  def create(payload: Any): Persistent =
    create(payload, null)

  /**
   * Java API.
   *
   * Creates a new persistent message, derived from the specified current message. The current
   * message can be obtained inside a [[Processor]] by calling `getCurrentPersistentMessage()`.
   *
   * @param payload payload of new persistent message.
   * @param currentPersistentMessage current persistent message.
   */
  def create(payload: Any, currentPersistentMessage: Persistent): Persistent =
    apply(payload)(Option(currentPersistentMessage))

  /**
   * Creates a new persistent message, derived from an implicit current message.
   * When used inside a [[Processor]], this is the optional current [[Message]]
   * of that processor.
   *
   * @param payload payload of the new persistent message.
   * @param currentPersistentMessage optional current persistent message, defaults to `None`.
   */
  def apply(payload: Any)(implicit currentPersistentMessage: Option[Persistent] = None): Persistent =
    currentPersistentMessage.map(_.withPayload(payload)).getOrElse(PersistentImpl(payload))

  /**
   * Persistent message extractor.
   */
  def unapply(persistent: Persistent): Option[(Any, Long)] =
    Some((persistent.payload, persistent.sequenceNr))
}

/**
 * INTERNAL API.
 *
 * Internal [[Persistent]] representation.
 */
private[persistence] case class PersistentImpl(
  payload: Any,
  sequenceNr: Long = 0L,
  resolved: Boolean = true,
  processorId: String = "",
  channelId: String = "",
  sender: String = "",
  confirms: Seq[String] = Nil,
  confirmTarget: ActorRef = null,
  confirmMessage: Confirm = null) extends Persistent {

  def withPayload(payload: Any): Persistent = copy(payload = payload)
  def confirm(): Unit = if (confirmTarget != null) confirmTarget ! confirmMessage
}

/**
 * Message to confirm the receipt of a persistent message (sent via a [[Channel]]).
 */
@SerialVersionUID(1L)
private[persistence] case class Confirm(processorId: String, sequenceNr: Long, channelId: String)
