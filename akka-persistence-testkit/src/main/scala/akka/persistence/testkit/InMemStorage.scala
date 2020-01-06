/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit

import java.util.{ List => JList }

import akka.util.ccompat.JavaConverters._
import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.annotation.InternalApi
import akka.persistence.testkit.ProcessingPolicy._
import akka.persistence._
import akka.persistence.testkit.internal.TestKitStorage
import akka.serialization.{ Serialization, SerializationExtension, Serializers }
import akka.persistence.testkit.scaladsl.{ PersistenceTestKit, SnapshotTestKit }

import scala.collection.immutable
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 */
@InternalApi
sealed trait EventStorage extends TestKitStorage[JournalOperation, PersistentRepr] {

  import EventStorage._

  def addAny(key: String, elem: Any): Unit =
    addAny(key, immutable.Seq(elem))

  def addAny(key: String, elems: immutable.Seq[Any]): Unit =
    // need to use `updateExisting` because `mapAny` reads latest seqnum
    // and therefore must be done at the same time with the update, not before
    updateOrSetNew(key, v => v ++ mapAny(key, elems).toVector)

  override def reprToSeqNum(repr: PersistentRepr): Long = repr.sequenceNr

  def add(elems: immutable.Seq[PersistentRepr]): Unit =
    elems.groupBy(_.persistenceId).foreach(gr => add(gr._1, gr._2))

  override protected val DefaultPolicy = JournalPolicies.PassAll

  /**
   * @throws Exception from StorageFailure in the current writing policy
   */
  def tryAdd(elems: immutable.Seq[PersistentRepr]): Try[Unit] = {
    val grouped = elems.groupBy(_.persistenceId)

    val processed = grouped.map {
      case (pid, els) => currentPolicy.tryProcess(pid, WriteEvents(els.map(_.payload)))
    }

    val reduced: ProcessingResult =
      processed.foldLeft[ProcessingResult](ProcessingSuccess)((left: ProcessingResult, right: ProcessingResult) =>
        (left, right) match {
          case (ProcessingSuccess, ProcessingSuccess) => ProcessingSuccess
          case (f: StorageFailure, _)                 => f
          case (_, f: StorageFailure)                 => f
          case (r: Reject, _)                         => r
          case (_, r: Reject)                         => r
        })

    reduced match {
      case ProcessingSuccess =>
        add(elems)
        Success(())
      case Reject(ex)         => Failure(ex)
      case StorageFailure(ex) => throw ex
    }
  }

  def tryRead(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      max: Long): immutable.Seq[PersistentRepr] = {
    val batch = read(persistenceId, fromSequenceNr, toSequenceNr, max)
    currentPolicy.tryProcess(persistenceId, ReadEvents(batch)) match {
      case ProcessingSuccess  => batch
      case Reject(ex)         => throw ex
      case StorageFailure(ex) => throw ex
    }
  }

  def tryReadSeqNumber(persistenceId: String): Long = {
    currentPolicy.tryProcess(persistenceId, ReadSeqNum) match {
      case ProcessingSuccess  => getHighestSeqNumber(persistenceId)
      case Reject(ex)         => throw ex
      case StorageFailure(ex) => throw ex
    }
  }

  def tryDelete(persistenceId: String, toSeqNumber: Long): Unit = {
    currentPolicy.tryProcess(persistenceId, DeleteEvents(toSeqNumber)) match {
      case ProcessingSuccess  => deleteToSeqNumber(persistenceId, toSeqNumber)
      case Reject(ex)         => throw ex
      case StorageFailure(ex) => throw ex
    }
  }

  private def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[PersistentRepr] = {
    val sn = getHighestSeqNumber(key) + 1
    elems.zipWithIndex.map(p => PersistentRepr(p._1, p._2 + sn, key))
  }

}

object EventStorage {

  object JournalPolicies extends DefaultPolicies[JournalOperation]

}

/**
 * INTERNAL API
 *
 * Persistent journal operations.
 */
@InternalApi
sealed trait JournalOperation

/**
 * Read from journal operation with events that were read.
 */
final case class ReadEvents(batch: immutable.Seq[Any]) extends JournalOperation {

  def getBatch(): JList[Any] = batch.asJava

}

/**
 * Write in journal operation with events to be written.
 */
final case class WriteEvents(batch: immutable.Seq[Any]) extends JournalOperation {

  def getBatch(): JList[Any] = batch.asJava

}

/**
 * Read persistent actor's sequence number operation.
 */
case object ReadSeqNum extends JournalOperation {

  /**
   * Java API: the singleton instance.
   */
  def getInstance() = this

}

/**
 * Delete events in the journal up to `toSeqNumber` operation.
 */
final case class DeleteEvents(toSeqNumber: Long) extends JournalOperation {

  def getToSeqNumber() = toSeqNumber

}

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] class SimpleEventStorageImpl extends EventStorage {

  override type InternalRepr = PersistentRepr

  override def toInternal(repr: PersistentRepr): PersistentRepr = repr

  override def toRepr(internal: PersistentRepr): PersistentRepr = internal

}

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] class SerializedEventStorageImpl(system: ActorSystem) extends EventStorage {

  override type InternalRepr = (Int, Array[Byte])

  private lazy val serialization = SerializationExtension(system)

  /**
   * @return (serializer id, serialized bytes)
   */
  override def toInternal(repr: PersistentRepr): (Int, Array[Byte]) =
    Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
      val s = serialization.findSerializerFor(repr)
      (s.identifier, s.toBinary(repr))
    }

  /**
   * @param internal (serializer id, serialized bytes)
   */
  override def toRepr(internal: (Int, Array[Byte])): PersistentRepr =
    serialization
      .deserialize(internal._2, internal._1, PersistentRepr.Undefined)
      .flatMap(r => Try(r.asInstanceOf[PersistentRepr]))
      .get

}

/**
 * INTERNAL API
 */
@InternalApi
sealed trait SnapshotStorage extends TestKitStorage[SnapshotOperation, (SnapshotMetadata, Any)] {

  import SnapshotStorage._

  override def reprToSeqNum(repr: (SnapshotMetadata, Any)): Long =
    repr._1.sequenceNr

  override protected val DefaultPolicy = SnapshotPolicies.PassAll

  def tryAdd(meta: SnapshotMetadata, payload: Any): Unit = {
    currentPolicy.tryProcess(meta.persistenceId, WriteSnapshot(SnapshotMeta(meta.sequenceNr, meta.timestamp), payload)) match {
      case ProcessingSuccess =>
        add(meta.persistenceId, (meta, payload))
        Success(())
      case f: ProcessingFailure => throw f.error

    }
  }

  def tryRead(persistenceId: String, criteria: SnapshotSelectionCriteria): Option[SelectedSnapshot] = {
    val selectedSnapshot =
      read(persistenceId).flatMap(
        _.reverseIterator.find(v => criteria.matches(v._1)).map(v => SelectedSnapshot(v._1, v._2)))
    currentPolicy.tryProcess(persistenceId, ReadSnapshot(criteria, selectedSnapshot.map(_.snapshot))) match {
      case ProcessingSuccess    => selectedSnapshot
      case f: ProcessingFailure => throw f.error
    }
  }

  def tryDelete(persistenceId: String, selectionCriteria: SnapshotSelectionCriteria): Unit = {
    currentPolicy.tryProcess(persistenceId, DeleteSnapshotsByCriteria(selectionCriteria)) match {
      case ProcessingSuccess =>
        delete(persistenceId, v => selectionCriteria.matches(v._1))
      case f: ProcessingFailure => throw f.error
    }
  }

  def tryDelete(meta: SnapshotMetadata): Unit = {
    currentPolicy.tryProcess(meta.persistenceId, DeleteSnapshotByMeta(SnapshotMeta(meta.sequenceNr, meta.timestamp))) match {
      case ProcessingSuccess =>
        delete(meta.persistenceId, _._1.sequenceNr == meta.sequenceNr)
      case f: ProcessingFailure => throw f.error
    }
  }

}

object SnapshotStorage {

  object SnapshotPolicies extends DefaultPolicies[SnapshotOperation]

}

/**
 * Snapshot metainformation.
 */
final case class SnapshotMeta(sequenceNr: Long, timestamp: Long = 0L) {

  def getSequenceNr() = sequenceNr

  def getTimestamp() = timestamp

}

case object SnapshotMeta {

  def create(sequenceNr: Long, timestamp: Long) =
    SnapshotMeta(sequenceNr, timestamp)

  def create(sequenceNr: Long) = SnapshotMeta(sequenceNr)

}

/**
 * INTERNAL API
 * Operations supported by snapshot plugin
 */
@InternalApi
sealed trait SnapshotOperation

/**
 *
 * Storage read operation for recovery of the persistent actor.
 *
 * @param criteria criteria with which snapshot is searched
 * @param snapshot snapshot found by criteria
 */
final case class ReadSnapshot(criteria: SnapshotSelectionCriteria, snapshot: Option[Any]) extends SnapshotOperation {

  def getSnapshotSelectionCriteria() = criteria

  def getSnapshot(): java.util.Optional[Any] =
    snapshot.map(java.util.Optional.of[Any]).getOrElse(java.util.Optional.empty[Any]())

}

/**
 * Storage write operation to persist snapshot in the storage.
 *
 * @param metadata snapshot metadata
 * @param snapshot snapshot payload
 */
final case class WriteSnapshot(metadata: SnapshotMeta, snapshot: Any) extends SnapshotOperation {

  def getMetadata() = metadata

  def getSnapshot() = snapshot

}

/**
 * INTERNAL API
 */
@InternalApi
sealed abstract class DeleteSnapshot extends SnapshotOperation

/**
 * Delete snapshots from storage by criteria.
 */
final case class DeleteSnapshotsByCriteria(criteria: SnapshotSelectionCriteria) extends DeleteSnapshot {

  def getCriteria() = criteria

}

/**
 * Delete particular snapshot from storage by its metadata.
 */
final case class DeleteSnapshotByMeta(metadata: SnapshotMeta) extends DeleteSnapshot {

  def getMetadata() = metadata

}

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] class SimpleSnapshotStorageImpl extends SnapshotStorage {

  override type InternalRepr = (SnapshotMetadata, Any)

  override def toRepr(internal: (SnapshotMetadata, Any)): (SnapshotMetadata, Any) = identity(internal)

  override def toInternal(repr: (SnapshotMetadata, Any)): (SnapshotMetadata, Any) = identity(repr)

}

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] class SerializedSnapshotStorageImpl(system: ActorSystem) extends SnapshotStorage {

  override type InternalRepr = (SnapshotMetadata, String, Int, Array[Byte])

  private lazy val serialization = SerializationExtension(system)

  override def toRepr(internal: (SnapshotMetadata, String, Int, Array[Byte])): (SnapshotMetadata, Any) =
    (internal._1, serialization.deserialize(internal._4, internal._3, internal._2).get)

  override def toInternal(repr: (SnapshotMetadata, Any)): (SnapshotMetadata, String, Int, Array[Byte]) =
    Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
      val payload = repr._2.asInstanceOf[AnyRef]
      val s = serialization.findSerializerFor(payload)
      val manifest = Serializers.manifestFor(s, payload)
      (repr._1, manifest, s.identifier, s.toBinary(payload))
    }

}

/**
 * INTERNAL API
 */
@InternalApi
object SnapshotStorageEmulatorExtension extends ExtensionId[SnapshotStorage] with ExtensionIdProvider {

  override def get(system: ActorSystem): SnapshotStorage = super.get(system)

  override def createExtension(system: ExtendedActorSystem): SnapshotStorage =
    if (SnapshotTestKit.Settings(system).serialize) {
      new SerializedSnapshotStorageImpl(system)
    } else {
      new SimpleSnapshotStorageImpl
    }

  override def lookup(): ExtensionId[_ <: Extension] =
    SnapshotStorageEmulatorExtension
}

/**
 * INTERNAL API
 */
@InternalApi
object InMemStorageExtension extends ExtensionId[EventStorage] with ExtensionIdProvider {

  override def get(system: ActorSystem): EventStorage = super.get(system)

  override def createExtension(system: ExtendedActorSystem) =
    if (PersistenceTestKit.Settings(system).serialize) {
      new SerializedEventStorageImpl(system)
    } else {
      new SimpleEventStorageImpl
    }

  override def lookup() = InMemStorageExtension

}
