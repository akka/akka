/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ List ⇒ JList }

import scala.collection.immutable

import akka.actor.ActorRef
import akka.japi.Util.immutableSeq

import akka.persistence.serialization.Message

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
}

object Persistent {
  /**
   * Java API: creates a new persistent message. Must only be used outside processors.
   *
   * @param payload payload of new persistent message.
   */
  def create(payload: Any): Persistent =
    create(payload, null)

  /**
   * Java API: creates a new persistent message, derived from the specified current message. The current
   * message can be obtained inside a [[Processor]] by calling `getCurrentPersistentMessage()`.
   *
   * @param payload payload of new persistent message.
   * @param currentPersistentMessage current persistent message.
   */
  def create(payload: Any, currentPersistentMessage: Persistent): Persistent =
    apply(payload)(Option(currentPersistentMessage))

  /**
   * Creates a new persistent message, derived from an implicit current message.
   * When used inside a [[Processor]], this is the optional current [[Persistent]]
   * message of that processor.
   *
   * @param payload payload of the new persistent message.
   * @param currentPersistentMessage optional current persistent message, defaults to `None`.
   */
  def apply(payload: Any)(implicit currentPersistentMessage: Option[Persistent] = None): Persistent =
    currentPersistentMessage.map(_.withPayload(payload)).getOrElse(PersistentRepr(payload))

  /**
   * [[Persistent]] extractor.
   */
  def unapply(persistent: Persistent): Option[(Any, Long)] =
    Some((persistent.payload, persistent.sequenceNr))
}

/**
 * Persistent message that has been delivered by a [[Channel]] or [[PersistentChannel]]. Channel
 * destinations that receive messages of this type can confirm their receipt by calling [[confirm]].
 */
sealed abstract class ConfirmablePersistent extends Persistent {
  /**
   * Called by [[Channel]] and [[PersistentChannel]] destinations to confirm the receipt of a
   * persistent message.
   */
  def confirm(): Unit
}

object ConfirmablePersistent {
  /**
   * [[ConfirmablePersistent]] extractor.
   */
  def unapply(persistent: ConfirmablePersistent): Option[(Any, Long)] =
    Some((persistent.payload, persistent.sequenceNr))
}

/**
 * Instructs a [[Processor]] to atomically write the contained [[Persistent]] messages to the
 * journal. The processor receives the written messages individually as [[Persistent]] messages.
 * During recovery, they are also replayed individually.
 */
case class PersistentBatch(persistentBatch: immutable.Seq[Persistent]) extends Message {
  /**
   * INTERNAL API.
   */
  private[persistence] def persistentReprList: List[PersistentRepr] =
    persistentBatch.toList.asInstanceOf[List[PersistentRepr]]
}

/**
 * Plugin API: representation of a persistent message in the journal plugin API.
 *
 * @see[[SyncWriteJournal]]
 * @see[[AsyncWriteJournal]]
 * @see[[AsyncReplay]]
 */
trait PersistentRepr extends Persistent with Message {
  import scala.collection.JavaConverters._

  /**
   * This persistent message's payload.
   */
  def payload: Any

  /**
   * This persistent message's seuence number.
   */
  def sequenceNr: Long

  /**
   * Id of processor that journals the message
   */
  def processorId: String

  /**
   * `true` if this message is marked as deleted.
   */
  def deleted: Boolean

  /**
   * `true` by default, `false` for replayed messages. Set to `true` by a channel if this
   * message is replayed and its sender reference was resolved. Channels use this field to
   * avoid redundant sender reference resolutions.
   */
  def resolved: Boolean

  /**
   * Channel ids of delivery confirmations that are available for this message. Only non-empty
   * for replayed messages.
   */
  def confirms: immutable.Seq[String]

  /**
   * Java API, Plugin API: channel ids of delivery confirmations that are available for this
   * message. Only non-empty for replayed messages.
   */
  def getConfirms: JList[String] = confirms.asJava

  /**
   * `true` only if this message has been delivered by a channel.
   */
  def confirmable: Boolean

  /**
   * Delivery confirmation message.
   */
  def confirmMessage: Confirm

  /**
   * Delivery confirmation message.
   */
  def confirmTarget: ActorRef

  /**
   * Sender of this message.
   */
  def sender: ActorRef

  private[persistence] def prepareWrite(sender: ActorRef): PersistentRepr

  private[persistence] def update(
    sequenceNr: Long = sequenceNr,
    processorId: String = processorId,
    deleted: Boolean = deleted,
    resolved: Boolean = resolved,
    confirms: immutable.Seq[String] = confirms,
    confirmMessage: Confirm = confirmMessage,
    confirmTarget: ActorRef = confirmTarget): PersistentRepr
}

object PersistentRepr {
  /**
   * Plugin API: value of an undefined processor or channel id.
   */
  val Undefined = ""

  /**
   * Plugin API.
   */
  def apply(
    payload: Any,
    sequenceNr: Long = 0L,
    processorId: String = PersistentRepr.Undefined,
    deleted: Boolean = false,
    resolved: Boolean = true,
    confirms: immutable.Seq[String] = Nil,
    confirmable: Boolean = false,
    confirmMessage: Confirm = null,
    confirmTarget: ActorRef = null,
    sender: ActorRef = null) =
    if (confirmable) ConfirmablePersistentImpl(payload, sequenceNr, processorId, deleted, resolved, confirms, confirmMessage, confirmTarget, sender)
    else PersistentImpl(payload, sequenceNr, processorId, deleted, confirms, sender)

  /**
   * Java API, Plugin API.
   */
  def create = apply _
}

object PersistentBatch {
  /**
   * Java API.
   */
  def create(persistentBatch: JIterable[Persistent]) =
    PersistentBatch(immutableSeq(persistentBatch))
}

/**
 * INTERNAL API.
 */
private[persistence] case class PersistentImpl(
  payload: Any,
  sequenceNr: Long,
  processorId: String,
  deleted: Boolean,
  confirms: immutable.Seq[String],
  sender: ActorRef) extends Persistent with PersistentRepr {

  def withPayload(payload: Any): Persistent =
    copy(payload = payload)

  def prepareWrite(sender: ActorRef) =
    copy(sender = sender)

  def update(
    sequenceNr: Long,
    processorId: String,
    deleted: Boolean,
    resolved: Boolean,
    confirms: immutable.Seq[String],
    confirmMessage: Confirm,
    confirmTarget: ActorRef) =
    copy(sequenceNr = sequenceNr, processorId = processorId, deleted = deleted, confirms = confirms)

  val resolved: Boolean = false
  val confirmable: Boolean = false
  val confirmMessage: Confirm = null
  val confirmTarget: ActorRef = null
}

/**
 * INTERNAL API.
 */
private[persistence] case class ConfirmablePersistentImpl(
  payload: Any,
  sequenceNr: Long,
  processorId: String,
  deleted: Boolean,
  resolved: Boolean,
  confirms: immutable.Seq[String],
  confirmMessage: Confirm,
  confirmTarget: ActorRef,
  sender: ActorRef) extends ConfirmablePersistent with PersistentRepr {

  def withPayload(payload: Any): Persistent =
    copy(payload = payload)

  def confirm(): Unit =
    if (confirmTarget != null) confirmTarget ! confirmMessage

  def confirmable = true

  def prepareWrite(sender: ActorRef) =
    copy(sender = sender, resolved = false, confirmMessage = null, confirmTarget = null)

  def update(sequenceNr: Long, processorId: String, deleted: Boolean, resolved: Boolean, confirms: immutable.Seq[String], confirmMessage: Confirm, confirmTarget: ActorRef) =
    copy(sequenceNr = sequenceNr, processorId = processorId, deleted = deleted, resolved = resolved, confirms = confirms, confirmMessage = confirmMessage, confirmTarget = confirmTarget)
}

private[persistence] object ConfirmablePersistentImpl {
  def apply(persistent: PersistentRepr, confirmMessage: Confirm, confirmTarget: ActorRef): ConfirmablePersistentImpl =
    ConfirmablePersistentImpl(persistent.payload, persistent.sequenceNr, persistent.processorId, persistent.deleted, persistent.resolved, persistent.confirms, confirmMessage, confirmTarget, persistent.sender)
}

/**
 * INTERNAL API.
 *
 * Message to confirm the receipt of a [[ConfirmablePersistent]] message.
 */
private[persistence] case class Confirm(processorId: String, sequenceNr: Long, channelId: String) extends Message
