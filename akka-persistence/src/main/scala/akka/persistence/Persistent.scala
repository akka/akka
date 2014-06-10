/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ List ⇒ JList }

import scala.collection.immutable

import akka.actor.{ ActorContext, ActorRef }
import akka.japi.Util.immutableSeq
import akka.pattern.PromiseActorRef
import akka.persistence.serialization.Message

/**
 * Marks messages which can be resequenced by the [[akka.persistence.journal.AsyncWriteJournal]].
 *
 * In essence it is either an [[NonPersistentRepr]] or [[Persistent]].
 */
sealed trait Resequenceable {
  def payload: Any
  def sender: ActorRef
}

/** Message which can be resequenced by the Journal, but will not be persisted. */
final case class NonPersistentRepr(payload: Any, sender: ActorRef) extends Resequenceable

/** Persistent message. */
@deprecated("Use akka.persistence.PersistentActor instead.", since = "2.3.4")
sealed abstract class Persistent extends Resequenceable {
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

@deprecated("Use akka.persistence.PersistentActor instead", since = "2.3.4")
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
  @deprecated("Use akka.persistence.PersistentActor instead", since = "2.3.4")
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
@deprecated("Use akka.persistence.PersistentActor instead", since = "2.3.4")
sealed abstract class ConfirmablePersistent extends Persistent {
  /**
   * Called by [[Channel]] and [[PersistentChannel]] destinations to confirm the receipt of a
   * persistent message.
   */
  def confirm(): Unit

  /**
   * Number of redeliveries. Only greater than zero if message has been redelivered by a [[Channel]]
   * or [[PersistentChannel]].
   */
  def redeliveries: Int
}

@deprecated("Use akka.persistence.PersistentActor instead", since = "2.3.4")
object ConfirmablePersistent {
  /**
   * [[ConfirmablePersistent]] extractor.
   */
  def unapply(persistent: ConfirmablePersistent): Option[(Any, Long, Int)] =
    Some((persistent.payload, persistent.sequenceNr, persistent.redeliveries))
}

/**
 * Instructs a [[Processor]] to atomically write the contained [[Persistent]] messages to the
 * journal. The processor receives the written messages individually as [[Persistent]] messages.
 * During recovery, they are also replayed individually.
 */
case class PersistentBatch(batch: immutable.Seq[Resequenceable]) extends Message

/**
 * Plugin API: confirmation entry written by journal plugins.
 */
trait PersistentConfirmation {
  def processorId: String
  def channelId: String
  def sequenceNr: Long
}

/**
 * Plugin API: persistent message identifier.
 */
trait PersistentId {
  /**
   * Id of processor that journals a persistent message
   */
  def processorId: String

  /**
   * A persistent message's sequence number.
   */
  def sequenceNr: Long
}

/**
 * INTERNAL API.
 */
private[persistence] final case class PersistentIdImpl(processorId: String, sequenceNr: Long) extends PersistentId

/**
 * Plugin API: representation of a persistent message in the journal plugin API.
 *
 * @see [[journal.SyncWriteJournal]]
 * @see [[journal.AsyncWriteJournal]]
 * @see [[journal.AsyncRecovery]]
 */
trait PersistentRepr extends Persistent with Resequenceable with PersistentId with Message {
  // todo we want to get rid of the Persistent() wrapper from user land; PersistentRepr is here to stay. #15230

  import scala.collection.JavaConverters._

  /**
   * This persistent message's payload.
   */
  def payload: Any

  /**
   * `true` if this message is marked as deleted.
   */
  def deleted: Boolean

  /**
   * Number of redeliveries. Only greater than zero if message has been redelivered by a [[Channel]]
   * or [[PersistentChannel]].
   */
  def redeliveries: Int

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
  def confirmMessage: Delivered

  /**
   * Delivery confirmation message.
   */
  def confirmTarget: ActorRef

  /**
   * Sender of this message.
   */
  def sender: ActorRef

  /**
   * INTERNAL API.
   */
  private[persistence] def prepareWrite(sender: ActorRef): PersistentRepr

  /**
   * INTERNAL API.
   */
  private[persistence] def prepareWrite()(implicit context: ActorContext): PersistentRepr =
    prepareWrite(if (sender.isInstanceOf[PromiseActorRef]) context.system.deadLetters else sender)

  /**
   * Creates a new copy of this [[PersistentRepr]].
   */
  def update(
    sequenceNr: Long = sequenceNr,
    processorId: String = processorId,
    deleted: Boolean = deleted,
    redeliveries: Int = redeliveries,
    confirms: immutable.Seq[String] = confirms,
    confirmMessage: Delivered = confirmMessage,
    confirmTarget: ActorRef = confirmTarget,
    sender: ActorRef = sender): PersistentRepr
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
    redeliveries: Int = 0,
    confirms: immutable.Seq[String] = Nil,
    confirmable: Boolean = false,
    confirmMessage: Delivered = null,
    confirmTarget: ActorRef = null,
    sender: ActorRef = null) =
    if (confirmable) ConfirmablePersistentImpl(payload, sequenceNr, processorId, deleted, redeliveries, confirms, confirmMessage, confirmTarget, sender)
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
private[persistence] final case class PersistentImpl(
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
    redeliveries: Int,
    confirms: immutable.Seq[String],
    confirmMessage: Delivered,
    confirmTarget: ActorRef,
    sender: ActorRef) =
    copy(sequenceNr = sequenceNr, processorId = processorId, deleted = deleted, confirms = confirms, sender = sender)

  val redeliveries: Int = 0
  val confirmable: Boolean = false
  val confirmMessage: Delivered = null
  val confirmTarget: ActorRef = null
}

/**
 * INTERNAL API.
 */
private[persistence] final case class ConfirmablePersistentImpl(
  payload: Any,
  sequenceNr: Long,
  processorId: String,
  deleted: Boolean,
  redeliveries: Int,
  confirms: immutable.Seq[String],
  confirmMessage: Delivered,
  confirmTarget: ActorRef,
  sender: ActorRef) extends ConfirmablePersistent with PersistentRepr {

  def withPayload(payload: Any): ConfirmablePersistent =
    copy(payload = payload)

  def confirm(): Unit =
    if (confirmTarget != null) confirmTarget ! confirmMessage

  def confirmable = true

  def prepareWrite(sender: ActorRef) =
    copy(sender = sender, confirmMessage = null, confirmTarget = null)

  def update(sequenceNr: Long, processorId: String, deleted: Boolean, redeliveries: Int, confirms: immutable.Seq[String], confirmMessage: Delivered, confirmTarget: ActorRef, sender: ActorRef) =
    copy(sequenceNr = sequenceNr, processorId = processorId, deleted = deleted, redeliveries = redeliveries, confirms = confirms, confirmMessage = confirmMessage, confirmTarget = confirmTarget, sender = sender)
}

/**
 * INTERNAL API.
 */
private[persistence] object ConfirmablePersistentImpl {
  def apply(persistent: PersistentRepr, confirmMessage: Delivered, confirmTarget: ActorRef = null): ConfirmablePersistentImpl =
    ConfirmablePersistentImpl(persistent.payload, persistent.sequenceNr, persistent.processorId, persistent.deleted, persistent.redeliveries, persistent.confirms, confirmMessage, confirmTarget, persistent.sender)
}
