/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.persistence.testkit.scaladsl.MessageStorage.JournalOperation
import akka.persistence.testkit.scaladsl.ProcessingPolicy._
import akka.persistence.testkit.scaladsl.SnapshotStorage.SnapshotOperation
import akka.persistence.{ PersistentRepr, SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }
import akka.serialization.SerializationExtension

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.{ Failure, Success, Try }

trait InternalReprSupport[T, R] {

  def toInternal(repr: R): T

  def toRepr(internal: T): R

}

trait InMemStorage[K, R, T] extends InternalReprSupport[T, R] {

  private val parallelism = Runtime.getRuntime.availableProcessors()

  private final val eventsMap: ConcurrentHashMap[K, Vector[T]] = new ConcurrentHashMap()

  def forEachKey(f: K ⇒ Unit): Unit = eventsMap.forEachKey(parallelism, f)

  def findMany(key: K, fromInclusive: Int, maxNum: Int): Option[Vector[R]] =
    read(key)
      .flatMap(value ⇒ if (value.size > fromInclusive) Some(value.drop(fromInclusive).take(maxNum)) else None)

  def findOneByIndex(key: K, index: Int): Option[R] =
    Option(eventsMap.get(key))
      .flatMap(value ⇒ if (value.size > index) Some(value(index)) else None)
      .map(toRepr)

  def addAny(key: K, elem: Any): Unit =
    addAny(key, immutable.Seq(elem))

  def mapAny(key: K, elems: immutable.Seq[Any]): immutable.Seq[R]

  def addAny(key: K, elems: immutable.Seq[Any]): Unit =
    add(key, mapAny(key, elems))

  def add(key: K, p: R): Unit =
    add(key, List(p))

  /**
   *
   * Note! `elems` is call by name to preserve atomicity in case of use of mapping with `def mapAny`
   *
   * @param key
   * @param elems elements to insert
   */
  def add(key: K, elems: ⇒ immutable.Seq[R]): Unit =
    eventsMap.compute(key, (_: K, value: Vector[T]) ⇒ value match {
      case null     ⇒ elems.map(toInternal).toVector
      case existing ⇒ existing ++ elems.map(toInternal)
    })

  def delete(key: K, needsToBeDeleted: R ⇒ Boolean): Vector[R] =
    eventsMap.computeIfPresent(key, (_: K, value: Vector[T]) ⇒ {
      value.map(toRepr).filterNot(needsToBeDeleted).map(toInternal)
    }).map(toRepr)

  def updateExisting(key: K, updater: Vector[R] ⇒ Vector[R]): Vector[R] =
    eventsMap.computeIfPresent(key, (_: K, value: Vector[T]) ⇒ {
      updater(value.map(toRepr)).map(toInternal)
    }).map(toRepr)

  def read(key: K): Option[Vector[R]] =
    Option(eventsMap.get(key)).map(_.map(toRepr))

  def clearAll(): Unit =
    eventsMap.clear()

  def removeKey(key: K): Vector[R] =
    eventsMap.remove(key).map(toRepr)

  def removeKey(key: K, value: Vector[R]): Boolean =
    eventsMap.remove(key, value)

  import java.util.{ function ⇒ jf }

  import scala.language.implicitConversions

  protected implicit def scalaFun1ToJava[I, Re](f: I ⇒ Re): jf.Function[I, Re] = new jf.Function[I, Re] {
    override def apply(t: I): Re = f(t)
  }

  protected implicit def scalaFunToConsumer[I](f: I ⇒ Unit): jf.Consumer[I] = new jf.Consumer[I] {
    override def accept(t: I): Unit = f(t)
  }

  protected implicit def scalaFun2ToJava[I, M, Re](f: (I, M) ⇒ Re): jf.BiFunction[I, M, Re] = new jf.BiFunction[I, M, Re] {
    override def apply(t: I, u: M): Re = f(t, u)
  }

}

trait HighestSeqNumSupportStorage[K, R, V] extends InMemStorage[K, R, V] {

  private final val seqNumbers = new ConcurrentHashMap[K, Long]()

  import scala.math._

  def reprToSeqNum(repr: R): Long

  def read(key: K, fromInclusive: Long, toInclusive: Long, maxNumber: Long): immutable.Seq[R] =
    read(key).getOrElse(Vector.empty)
      .dropWhile(reprToSeqNum(_) < fromInclusive)
      //we dont need to read highestSeqNumber because it will in any case stop at it if toInclusive > highestSeqNumber
      .takeWhile(reprToSeqNum(_) <= toInclusive)
      .take(if (maxNumber > Int.MaxValue) Int.MaxValue else maxNumber.toInt)

  @tailrec
  final def removePreservingSeqNumber(key: K): Unit = {
    val value = read(key)
    value match {
      case Some(v) ⇒
        reloadHighestSequenceNum(key)
        if (!removeKey(key, v)) removePreservingSeqNumber(key)
      case None ⇒
    }
  }

  def reloadHighestSequenceNum(key: K): Long =
    seqNumbers.compute(key, (_: K, sn: Long) ⇒ {
      val savedSn = Option(sn)
      val storeSn =
        read(key)
          .flatMap(_.lastOption)
          .map(reprToSeqNum)
      (for {
        fsn ← savedSn
        ssn ← storeSn
      } yield max(fsn, ssn))
        .orElse(savedSn)
        .orElse(storeSn)
        .getOrElse(0L)
    })

  def deleteToSeqNumber(key: K, toSeqNumberInclusive: Long): Unit =
    updateExisting(key, value ⇒ {
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

trait PolicyOps[U] {

  type Policy = ProcessingPolicy[U]

  protected val DefaultPolicy: Policy

  private lazy val _processingPolicy: AtomicReference[Policy] = new AtomicReference(DefaultPolicy)

  def currentPolicy = _processingPolicy.get()

  def setPolicy(policy: Policy) = _processingPolicy.set(policy)

  def compareAndSetWritingPolicy(previousPolicy: Policy, newPolicy: Policy) = _processingPolicy.compareAndSet(previousPolicy, newPolicy)

}

trait TestKitStorage[R, P, I] extends HighestSeqNumSupportStorage[String, R, I] with PolicyOps[P] with Extension

trait MessageStorage[I] extends TestKitStorage[PersistentRepr, JournalOperation, I] {
  import MessageStorage._

  override def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[PersistentRepr] = {
    val sn = reloadHighestSequenceNum(key) + 1
    elems.zipWithIndex.map(p ⇒ PersistentRepr(p._1, p._2 + sn, key))
  }

  override def reprToSeqNum(repr: PersistentRepr): Long = repr.sequenceNr

  def add(elems: immutable.Seq[PersistentRepr]): Unit =
    elems
      .groupBy(_.persistenceId)
      .foreach(gr ⇒ add(gr._1, gr._2))

  override protected val DefaultPolicy = JournalPolicies.PassAll

  /**
   *
   * @throws Exception from StorageFailure in the current writing policy
   */
  def tryAdd(elems: immutable.Seq[PersistentRepr]): Try[Unit] = {
    val grouped = elems.groupBy(_.persistenceId)

    val processed = grouped.map {
      case (pid, els) ⇒ currentPolicy.tryProcess(pid, Write(els.map(_.payload)))
    }

    val reduced: ProcessingResult = processed.foldLeft[ProcessingResult](ProcessingSuccess)((left: ProcessingResult, right: ProcessingResult) ⇒ (left, right) match {
      case (ProcessingSuccess, ProcessingSuccess) ⇒ ProcessingSuccess
      case (f: StorageFailure, _)                 ⇒ f
      case (_, f: StorageFailure)                 ⇒ f
      case (r: Reject, _)                         ⇒ r
      case (_, r: Reject)                         ⇒ r
    })

    reduced match {
      case ProcessingSuccess ⇒
        add(elems)
        Success(())
      case Reject(ex)         ⇒ Failure(ex)
      case StorageFailure(ex) ⇒ throw ex
    }
  }

  def tryRead(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long): immutable.Seq[PersistentRepr] = {
    val batch = read(persistenceId, fromSequenceNr, toSequenceNr, max)
    currentPolicy.tryProcess(persistenceId, Read(batch)) match {
      case ProcessingSuccess  ⇒ batch
      case Reject(ex)         ⇒ throw ex
      case StorageFailure(ex) ⇒ throw ex
    }
  }

  def tryReadSeqNumber(persistenceId: String): Long = {
    currentPolicy.tryProcess(persistenceId, ReadSeqNum) match {
      case ProcessingSuccess  ⇒ reloadHighestSequenceNum(persistenceId)
      case Reject(ex)         ⇒ throw ex
      case StorageFailure(ex) ⇒ throw ex
    }
  }

  def tryDelete(persistenceId: String, toSeqNumber: Long): Unit = {
    currentPolicy.tryProcess(persistenceId, Delete(toSeqNumber)) match {
      case ProcessingSuccess  ⇒ deleteToSeqNumber(persistenceId, toSeqNumber)
      case Reject(ex)         ⇒ throw ex
      case StorageFailure(ex) ⇒ throw ex
    }
  }

}

object MessageStorage {

  trait JournalOperation

  case class Read(batch: immutable.Seq[Any]) extends JournalOperation

  case class Write(batch: immutable.Seq[Any]) extends JournalOperation

  case object ReadSeqNum extends JournalOperation

  case class Delete(toSeqNumber: Long) extends JournalOperation

  object JournalPolicies extends DefaultPolicies[JournalOperation]

}

class SimpleMessageStorageImpl extends MessageStorage[PersistentRepr] {

  override def toInternal(repr: PersistentRepr): PersistentRepr = repr

  override def toRepr(internal: PersistentRepr): PersistentRepr = internal

}

class SerializedMessageStorageImpl(system: ActorSystem) extends MessageStorage[(Int, Array[Byte])] {

  private lazy val serialization = SerializationExtension(system)

  override def toInternal(repr: PersistentRepr): (Int, Array[Byte]) = {
    val s = serialization.findSerializerFor(repr)
    (s.identifier, s.toBinary(repr))
  }

  override def toRepr(internal: (Int, Array[Byte])): PersistentRepr =
    serialization.deserialize(internal._2, internal._1, Some(classOf[PersistentRepr])).get

}

trait SnapshotStorage[I] extends TestKitStorage[(SnapshotMetadata, Any), SnapshotOperation, I] {
  import SnapshotStorage._

  override def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[(SnapshotMetadata, Any)] = {
    val sn = reloadHighestSequenceNum(key)
    elems.zipWithIndex.map(p ⇒ (SnapshotMetadata(key, p._2 + sn), p._1))
  }

  override def reprToSeqNum(repr: (SnapshotMetadata, Any)): Long = repr._1.sequenceNr

  override protected val DefaultPolicy = SnapshotPolicies.PassAll

  def tryAdd(meta: SnapshotMetadata, payload: Any): Unit = {
    currentPolicy.tryProcess(meta.persistenceId, Write(payload)) match {
      case ProcessingSuccess ⇒
        add(meta.persistenceId, (meta, payload))
        Success(())
      case f: ProcessingFailure ⇒ throw f.error

    }
  }

  def tryRead(persistenceId: String, criteria: SnapshotSelectionCriteria): Option[SelectedSnapshot] = {
    val selectedSnapshot =
      read(persistenceId)
        .flatMap(_.reverseIterator.find(v ⇒ criteria.matches(v._1))
          .map(v ⇒ SelectedSnapshot(v._1, v._2)))
    currentPolicy.tryProcess(persistenceId, Read(selectedSnapshot.map(_.snapshot))) match {
      case ProcessingSuccess    ⇒ selectedSnapshot
      case f: ProcessingFailure ⇒ throw f.error
    }
  }

  def tryDelete(persistenceId: String, selectionCriteria: SnapshotSelectionCriteria): Unit = {
    currentPolicy.tryProcess(persistenceId, DeleteByCriteria(selectionCriteria)) match {
      case ProcessingSuccess    ⇒ delete(persistenceId, v ⇒ selectionCriteria.matches(v._1))
      case f: ProcessingFailure ⇒ throw f.error
    }
  }

  def tryDelete(meta: SnapshotMetadata): Unit = {
    currentPolicy.tryProcess(meta.persistenceId, DeleteSnapshot(meta)) match {
      case ProcessingSuccess    ⇒ delete(meta.persistenceId, _._1.sequenceNr == meta.sequenceNr)
      case f: ProcessingFailure ⇒ throw f.error
    }
  }

}

object SnapshotStorage {

  trait SnapshotOperation

  case class Read(snapshot: Option[Any]) extends SnapshotOperation

  case class Write(snapshot: Any) extends SnapshotOperation

  abstract class Delete extends SnapshotOperation

  case class DeleteByCriteria(criteria: SnapshotSelectionCriteria) extends Delete

  case class DeleteSnapshot(metadata: SnapshotMetadata) extends Delete

  object SnapshotPolicies extends DefaultPolicies[SnapshotOperation]

}

class SimpleSnapshotStorageImpl extends SnapshotStorage[(SnapshotMetadata, Any)] {

  override def toRepr(internal: (SnapshotMetadata, Any)): (SnapshotMetadata, Any) = identity(internal)

  override def toInternal(repr: (SnapshotMetadata, Any)): (SnapshotMetadata, Any) = identity(repr)

}

class SerializedSnapshotStorageImpl(system: ActorSystem) extends SnapshotStorage[(SnapshotMetadata, Class[_], Int, Array[Byte])] {

  private lazy val serialization = SerializationExtension(system)

  override def toRepr(internal: (SnapshotMetadata, Class[_], Int, Array[Byte])): (SnapshotMetadata, Any) =
    (internal._1, serialization.deserialize(internal._4, internal._3, Some(internal._2)).get)

  override def toInternal(repr: (SnapshotMetadata, Any)): (SnapshotMetadata, Class[_], Int, Array[Byte]) = {
    val s = serialization.findSerializerFor(repr._2.asInstanceOf[AnyRef])
    (repr._1, repr._2.getClass, s.identifier, s.toBinary(repr._2.asInstanceOf[AnyRef]))
  }

}

object SnapshotStorageEmulatorExtension extends ExtensionId[SnapshotStorage[_]] with ExtensionIdProvider {

  override def get(system: ActorSystem): SnapshotStorage[_] = super.get(system)

  override def createExtension(system: ExtendedActorSystem): SnapshotStorage[_] =
    if (SnapshotTestKit.Settings(system).serialize) {
      new SerializedSnapshotStorageImpl(system)
    } else {
      new SimpleSnapshotStorageImpl
    }

  override def lookup(): ExtensionId[_ <: Extension] = SnapshotStorageEmulatorExtension
}

object InMemStorageExtension extends ExtensionId[MessageStorage[_]] with ExtensionIdProvider {

  override def get(system: ActorSystem): MessageStorage[_] = super.get(system)

  override def createExtension(system: ExtendedActorSystem) =
    if (PersistenceTestKit.Settings(system).serialize) {
      new SerializedMessageStorageImpl(system)
    } else {
      new SimpleMessageStorageImpl
    }

  override def lookup() = InMemStorageExtension

}

