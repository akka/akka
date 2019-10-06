/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.{ List => JList }

import scala.collection.JavaConverters._
import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.annotation.InternalApi
import akka.persistence.testkit.ProcessingPolicy._
import akka.persistence._
import akka.serialization.{ Serialization, SerializationExtension, Serializers }
import akka.persistence.testkit.scaladsl.{ PersistenceTestKit, SnapshotTestKit }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 */
@InternalApi
sealed trait InternalReprSupport[R] {

  type InternalRepr

  private[testkit] def toInternal(repr: R): InternalRepr

  private[testkit] def toRepr(internal: InternalRepr): R

}

/**
 * INTERNAL API
 */
@InternalApi
sealed trait InMemStorage[K, R] extends InternalReprSupport[R] {

  import akka.persistence.testkit.Utils.JavaFuncConversions._

  private val parallelism = Runtime.getRuntime.availableProcessors()

  private final val eventsMap: ConcurrentHashMap[K, Vector[InternalRepr]] =
    new ConcurrentHashMap()

  def forEachKey(f: K => Unit): Unit = eventsMap.forEachKey(parallelism, f)

  def findMany(key: K, fromInclusive: Int, maxNum: Int): Option[Vector[R]] =
    read(key).flatMap(
      value =>
        if (value.size > fromInclusive)
          Some(value.drop(fromInclusive).take(maxNum))
        else None)

  def findOneByIndex(key: K, index: Int): Option[R] =
    Option(eventsMap.get(key)).flatMap(value => if (value.size > index) Some(value(index)) else None).map(toRepr)

  def add(key: K, p: R): Unit =
    add(key, List(p))

  /**
   *
   * Note! `elems` is call by name to preserve atomicity
   *
   * @param elems elements to insert
   */
  def add(key: K, elems: => immutable.Seq[R]): Unit =
    eventsMap.compute(
      key,
      (_: K, value: Vector[InternalRepr]) =>
        value match {
          case null     => elems.map(toInternal).toVector
          case existing => existing ++ elems.map(toInternal)
        })

  def delete(key: K, needsToBeDeleted: R => Boolean): Vector[R] =
    eventsMap
      .computeIfPresent(key, (_: K, value: Vector[InternalRepr]) => {
        value.map(toRepr).filterNot(needsToBeDeleted).map(toInternal)
      })
      .map(toRepr)

  def updateExisting(key: K, updater: Vector[R] => Vector[R]): Vector[R] =
    eventsMap
      .computeIfPresent(key, (_: K, value: Vector[InternalRepr]) => {
        updater(value.map(toRepr)).map(toInternal)
      })
      .map(toRepr)

  def read(key: K): Option[Vector[R]] =
    Option(eventsMap.get(key)).map(_.map(toRepr))

  protected def readInternal(key: K): Option[Vector[InternalRepr]] =
    Option(eventsMap.get(key))

  def clearAll(): Unit =
    eventsMap.clear()

  def removeKey(key: K): Vector[R] =
    Option(eventsMap.remove(key)).getOrElse(Vector.empty).map(toRepr)

  protected def removeKeyInternal(key: K, value: Vector[InternalRepr]): Boolean =
    eventsMap.remove(key, value)

}

/**
 * INTERNAL API
 */
@InternalApi
sealed trait HighestSeqNumSupportStorage[K, R] extends InMemStorage[K, R] {

  private final val seqNumbers = new ConcurrentHashMap[K, Long]()

  import scala.math._

  def reprToSeqNum(repr: R): Long

  def read(key: K, fromInclusive: Long, toInclusive: Long, maxNumber: Long): immutable.Seq[R] =
    read(key)
      .getOrElse(Vector.empty)
      .dropWhile(reprToSeqNum(_) < fromInclusive)
      //we dont need to read highestSeqNumber because it will in any case stop at it if toInclusive > highestSeqNumber
      .takeWhile(reprToSeqNum(_) <= toInclusive)
      .take(if (maxNumber > Int.MaxValue) Int.MaxValue else maxNumber.toInt)

  override def removeKey(key: K): Vector[R] = {
    var re: Vector[R] = Vector.empty
    seqNumbers.compute(key, (_, _) => {
      re = super.removeKey(key)
      null: java.lang.Long
    })
    re
  }

  @tailrec
  final def removePreservingSeqNumber(key: K): Unit = {
    val value = readInternal(key)
    value match {
      case Some(v) =>
        reloadHighestSequenceNum(key)
        if (!removeKeyInternal(key, v)) removePreservingSeqNumber(key)
      case None =>
    }
  }

  def reloadHighestSequenceNum(key: K): Long =
    seqNumbers.compute(
      key,
      (_: K, sn: Long) => {
        val savedSn = Option(sn)
        val storeSn =
          read(key).flatMap(_.lastOption).map(reprToSeqNum)
        (for {
          fsn <- savedSn
          ssn <- storeSn
        } yield max(fsn, ssn)).orElse(savedSn).orElse(storeSn).getOrElse(0L)
      })

  def deleteToSeqNumber(key: K, toSeqNumberInclusive: Long): Unit =
    updateExisting(key, value => {
      reloadHighestSequenceNum(key)
      value.dropWhile(reprToSeqNum(_) <= toSeqNumberInclusive)
    })

  override def clearAll(): Unit = {
    seqNumbers.clear()
    super.clearAll()
  }

  def clearAllPreservingSeqNumbers(): Unit =
    forEachKey(removePreservingSeqNumber)

}

/**
 * INTERNAL API
 */
@InternalApi
sealed trait PolicyOps[U] {

  type Policy = ProcessingPolicy[U]

  protected val DefaultPolicy: Policy

  private lazy val _processingPolicy: AtomicReference[Policy] =
    new AtomicReference(DefaultPolicy)

  def currentPolicy = _processingPolicy.get()

  def setPolicy(policy: Policy) = _processingPolicy.set(policy)

  def returnDefaultPolicy(): Unit = setPolicy(DefaultPolicy)

  def compareAndSetWritingPolicy(previousPolicy: Policy, newPolicy: Policy) =
    _processingPolicy.compareAndSet(previousPolicy, newPolicy)

}

/**
 * INTERNAL API
 */
@InternalApi
sealed trait TestKitStorage[P, R] extends HighestSeqNumSupportStorage[String, R] with PolicyOps[P] with Extension

/**
 * INTERNAL API
 */
@InternalApi
sealed trait MessageStorage extends TestKitStorage[JournalOperation, PersistentRepr] {

  import MessageStorage._

  def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[PersistentRepr] = {
    val sn = reloadHighestSequenceNum(key) + 1
    elems.zipWithIndex.map(p => PersistentRepr(p._1, p._2 + sn, key))
  }

  def addAny(key: String, elem: Any): Unit =
    addAny(key, immutable.Seq(elem))

  def addAny(key: String, elems: immutable.Seq[Any]): Unit =
    add(key, mapAny(key, elems))

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
      case (pid, els) => currentPolicy.tryProcess(pid, WriteMessages(els.map(_.payload)))
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
    currentPolicy.tryProcess(persistenceId, ReadMessages(batch)) match {
      case ProcessingSuccess  => batch
      case Reject(ex)         => throw ex
      case StorageFailure(ex) => throw ex
    }
  }

  def tryReadSeqNumber(persistenceId: String): Long = {
    currentPolicy.tryProcess(persistenceId, ReadSeqNum) match {
      case ProcessingSuccess  => reloadHighestSequenceNum(persistenceId)
      case Reject(ex)         => throw ex
      case StorageFailure(ex) => throw ex
    }
  }

  def tryDelete(persistenceId: String, toSeqNumber: Long): Unit = {
    currentPolicy.tryProcess(persistenceId, DeleteMessages(toSeqNumber)) match {
      case ProcessingSuccess  => deleteToSeqNumber(persistenceId, toSeqNumber)
      case Reject(ex)         => throw ex
      case StorageFailure(ex) => throw ex
    }
  }

}

object MessageStorage {

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
 * Read from journal operation with messages that were read.
 */
final case class ReadMessages(batch: immutable.Seq[Any]) extends JournalOperation {

  def getBatch(): JList[Any] = batch.asJava

}

/**
 * Write in journal operation with messages to be written.
 */
final case class WriteMessages(batch: immutable.Seq[Any]) extends JournalOperation {

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
 * Delete messages in journal up to `toSeqNumber` operation.
 */
final case class DeleteMessages(toSeqNumber: Long) extends JournalOperation {

  def getToSeqNumber() = toSeqNumber

}

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] class SimpleMessageStorageImpl extends MessageStorage {

  override type InternalRepr = PersistentRepr

  override def toInternal(repr: PersistentRepr): PersistentRepr = repr

  override def toRepr(internal: PersistentRepr): PersistentRepr = internal

}

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] class SerializedMessageStorageImpl(system: ActorSystem) extends MessageStorage {

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
object InMemStorageExtension extends ExtensionId[MessageStorage] with ExtensionIdProvider {

  override def get(system: ActorSystem): MessageStorage = super.get(system)

  override def createExtension(system: ExtendedActorSystem) =
    if (PersistenceTestKit.Settings(system).serialize) {
      new SerializedMessageStorageImpl(system)
    } else {
      new SimpleMessageStorageImpl
    }

  override def lookup() = InMemStorageExtension

}
