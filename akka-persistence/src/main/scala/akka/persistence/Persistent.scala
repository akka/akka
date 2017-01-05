/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence

import akka.actor.{ ActorRef, NoSerializationVerificationNeeded }
import akka.persistence.serialization.Message

import scala.collection.immutable

/**
 * INTERNAL API
 *
 * Marks messages which can be resequenced by the [[akka.persistence.journal.AsyncWriteJournal]].
 *
 * In essence it is either an [[NonPersistentRepr]] or [[AtomicWrite]].
 */
private[persistence] sealed trait PersistentEnvelope {
  def payload: Any
  def sender: ActorRef
  def size: Int
}

/**
 * INTERNAL API
 * Message which can be resequenced by the Journal, but will not be persisted.
 */
private[persistence] final case class NonPersistentRepr(payload: Any, sender: ActorRef) extends PersistentEnvelope {
  override def size: Int = 1
}

object AtomicWrite {
  def apply(event: PersistentRepr): AtomicWrite = apply(List(event))
}

final case class AtomicWrite(payload: immutable.Seq[PersistentRepr]) extends PersistentEnvelope with Message {
  require(payload.nonEmpty, "payload of AtomicWrite must not be empty!")

  // only check that all persistenceIds are equal when there's more than one in the Seq
  if (payload match {
    case l: List[PersistentRepr]   ⇒ l.tail.nonEmpty // avoids calling .size
    case v: Vector[PersistentRepr] ⇒ v.size > 1
    case _                         ⇒ true // some other collection type, let's just check
  }) require(
    payload.forall(_.persistenceId == payload.head.persistenceId),
    "AtomicWrite must contain messages for the same persistenceId, " +
      s"yet different persistenceIds found: ${payload.map(_.persistenceId).toSet}")

  def persistenceId = payload.head.persistenceId
  def lowestSequenceNr = payload.head.sequenceNr // this assumes they're gapless; they should be (it is only our code creating AWs)
  def highestSequenceNr = payload.last.sequenceNr // TODO: could be optimised, since above require traverses already

  override def sender: ActorRef = ActorRef.noSender
  override def size: Int = payload.size
}

/**
 * Plugin API: representation of a persistent message in the journal plugin API.
 *
 * @see [[akka.persistence.journal.AsyncWriteJournal]]
 * @see [[akka.persistence.journal.AsyncRecovery]]
 */
trait PersistentRepr extends Message {

  /**
   * This persistent message's payload.
   */
  def payload: Any

  /**
   * Returns the persistent payload's manifest if available
   */
  def manifest: String

  /**
   * Persistent id that journals a persistent message
   */
  def persistenceId: String

  /**
   * This persistent message's sequence number.
   */
  def sequenceNr: Long

  /**
   * Unique identifier of the writing persistent actor.
   * Used to detect anomalies with overlapping writes from multiple
   * persistent actors, which can result in inconsistent replays.
   */
  def writerUuid: String

  /**
   * Creates a new persistent message with the specified `payload`.
   */
  def withPayload(payload: Any): PersistentRepr

  /**
   * Creates a new persistent message with the specified `manifest`.
   */
  def withManifest(manifest: String): PersistentRepr

  /**
   * Not used in new records stored with Akka v2.4, but
   * old records from v2.3 may have this as `true` if
   * it was a non-permanent delete.
   */
  def deleted: Boolean

  /**
   * Sender of this message.
   */
  def sender: ActorRef

  /**
   * Creates a new copy of this [[PersistentRepr]].
   */
  def update(
    sequenceNr:    Long     = sequenceNr,
    persistenceId: String   = persistenceId,
    deleted:       Boolean  = deleted,
    sender:        ActorRef = sender,
    writerUuid:    String   = writerUuid): PersistentRepr
}

object PersistentRepr {
  /** Plugin API: value of an undefined persistenceId or manifest. */
  val Undefined = ""
  /** Plugin API: value of an undefined / identity event adapter. */
  val UndefinedId = 0

  /**
   * Plugin API.
   */
  def apply(
    payload:       Any,
    sequenceNr:    Long     = 0L,
    persistenceId: String   = PersistentRepr.Undefined,
    manifest:      String   = PersistentRepr.Undefined,
    deleted:       Boolean  = false,
    sender:        ActorRef = null,
    writerUuid:    String   = PersistentRepr.Undefined): PersistentRepr =
    PersistentImpl(payload, sequenceNr, persistenceId, manifest, deleted, sender, writerUuid)

  /**
   * Java API, Plugin API.
   */
  def create = apply _

  /**
   * extractor of payload and sequenceNr.
   */
  def unapply(persistent: PersistentRepr): Option[(Any, Long)] =
    Some((persistent.payload, persistent.sequenceNr))
}

/**
 * INTERNAL API.
 */
private[persistence] final case class PersistentImpl(
  override val payload:       Any,
  override val sequenceNr:    Long,
  override val persistenceId: String,
  override val manifest:      String,
  override val deleted:       Boolean,
  override val sender:        ActorRef,
  override val writerUuid:    String) extends PersistentRepr with NoSerializationVerificationNeeded {

  def withPayload(payload: Any): PersistentRepr =
    copy(payload = payload)

  def withManifest(manifest: String): PersistentRepr =
    if (this.manifest == manifest) this
    else copy(manifest = manifest)

  def update(sequenceNr: Long, persistenceId: String, deleted: Boolean, sender: ActorRef, writerUuid: String) =
    copy(
      sequenceNr = sequenceNr,
      persistenceId = persistenceId,
      deleted = deleted,
      sender = sender,
      writerUuid = writerUuid)

}

