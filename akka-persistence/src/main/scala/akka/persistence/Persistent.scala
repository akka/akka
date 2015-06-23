/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.collection.immutable
import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ List ⇒ JList }

import akka.actor.{ ActorContext, ActorRef }
import akka.pattern.PromiseActorRef
import akka.persistence.serialization.Message

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
  override def sender: ActorRef = ActorRef.noSender
  override def size: Int = payload.size
}

/**
 * Plugin API: representation of a persistent message in the journal plugin API.
 *
 * @see [[akka.persistence.journal.SyncWriteJournal]]
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
   * Creates a new persistent message with the specified `payload`.
   */
  def withPayload(payload: Any): PersistentRepr

  /**
   * Creates a new persistent message with the specified `manifest`.
   */
  def withManifest(manifest: String): PersistentRepr

  /**
   * `true` if this message is marked as deleted.
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
    sequenceNr: Long = sequenceNr,
    persistenceId: String = persistenceId,
    deleted: Boolean = deleted,
    sender: ActorRef = sender): PersistentRepr
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
    payload: Any,
    sequenceNr: Long = 0L,
    persistenceId: String = PersistentRepr.Undefined,
    manifest: String = PersistentRepr.Undefined,
    deleted: Boolean = false,
    sender: ActorRef = null): PersistentRepr =
    PersistentImpl(payload, sequenceNr, persistenceId, manifest, deleted, sender)

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
  override val payload: Any,
  override val sequenceNr: Long,
  override val persistenceId: String,
  override val manifest: String,
  override val deleted: Boolean,
  override val sender: ActorRef) extends PersistentRepr {

  def withPayload(payload: Any): PersistentRepr =
    copy(payload = payload)

  def withManifest(manifest: String): PersistentRepr =
    if (this.manifest == manifest) this
    else copy(manifest = manifest)

  def update(sequenceNr: Long, persistenceId: String, deleted: Boolean, sender: ActorRef) =
    copy(sequenceNr = sequenceNr, persistenceId = persistenceId, deleted = deleted, sender = sender)

}

