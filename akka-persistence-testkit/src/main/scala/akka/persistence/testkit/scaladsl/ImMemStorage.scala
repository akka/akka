/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.{ BiFunction, Consumer }

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.persistence.testkit.scaladsl.InMemStorageEmulator.JournalPolicy
import akka.persistence.testkit.scaladsl.ProcessingPolicy._
import akka.persistence.testkit.scaladsl.SnapShotStorageEmulator._
import akka.persistence.{ PersistentRepr, SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.{ Failure, Success, Try }

trait InMemStorage[K, T] {

  private val parallelism = Runtime.getRuntime.availableProcessors()

  private final val eventsMap: ConcurrentHashMap[K, Vector[T]] = new ConcurrentHashMap()

  def forEachKey(f: K ⇒ Unit) = eventsMap.forEachKey(parallelism, f)

  def findMany(key: K, fromInclusive: Int, maxNum: Int): Option[Vector[T]] =
    read(key)
      .flatMap(value ⇒ if (value.size > fromInclusive) Some(value.drop(fromInclusive).take(maxNum)) else None)

  def findOneByIndex(key: K, index: Int): Option[T] =
    Option(eventsMap.get(key))
      .flatMap(value ⇒ if (value.size > index) Some(value(index)) else None)

  def addAny(key: K, elem: Any): Unit =
    addAny(key, immutable.Seq(elem))

  def mapAny(key: K, elems: immutable.Seq[Any]): immutable.Seq[T]

  def addAny(key: K, elems: immutable.Seq[Any]): Unit =
    add(key, mapAny(key, elems))

  def add(key: K, p: T): Unit =
    add(key, List(p))

  /**
   *
   * Note! `elems` is call by name to preserve atomicity in case of use of mapping with `def mapAny`
   *
   * @param key
   * @param elems elements to insert
   */
  def add(key: K, elems: ⇒ immutable.Seq[T]): Unit =
    eventsMap.compute(key, (_: K, value: Vector[T]) ⇒ value match {
      case null     ⇒ elems.toVector
      case existing ⇒ existing ++ elems
    })

  def delete(key: K, needsToBeDeleted: T ⇒ Boolean) =
    eventsMap.computeIfPresent(key, (_: K, value: Vector[T]) ⇒ {
      value.filterNot(needsToBeDeleted)
    })

  def updateExisting(key: K, updater: Vector[T] ⇒ Vector[T]) =
    eventsMap.computeIfPresent(key, (_: K, value: Vector[T]) ⇒ {
      updater(value)
    })

  def read(key: K): Option[Vector[T]] =
    Option(eventsMap.get(key))

  def clearAll() =
    eventsMap.clear()

  def removeKey(key: K) =
    eventsMap.remove(key)

  def removeKey(key: K, value: Vector[T]): Boolean =
    eventsMap.remove(key, value)

  import java.util.{ function ⇒ jf }

  import scala.language.implicitConversions

  protected implicit def scalaFun1ToJava[I, R](f: I ⇒ R): jf.Function[I, R] = new jf.Function[I, R] {
    override def apply(t: I): R = f(t)
  }

  protected implicit def scalaFunToConsumer[I](f: I ⇒ Unit): jf.Consumer[I] = new Consumer[I] {
    override def accept(t: I): Unit = f(t)
  }

  protected implicit def scalaFun2ToJava[I, M, R](f: (I, M) ⇒ R): jf.BiFunction[I, M, R] = new BiFunction[I, M, R] {
    override def apply(t: I, u: M): R = f(t, u)
  }

}

trait HighestSeqNumberSupport[K, V] extends InMemStorage[K, V] {

  private final val seqNumbers = new ConcurrentHashMap[K, Long]()

  import scala.math._

  def reprToSeqNum(repr: V): Long

  def read(key: K, fromInclusive: Long, toInclusive: Long, maxNumber: Long): immutable.Seq[V] =
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

  def clearAllPreservingSeqNumbers() =
    forEachKey(removePreservingSeqNumber)

}

trait ReprInMemStorage extends HighestSeqNumberSupport[String, PersistentRepr] {

  override def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[PersistentRepr] = {
    val sn = reloadHighestSequenceNum(key) + 1
    elems.zipWithIndex.map(p ⇒ PersistentRepr(p._1, p._2 + sn, key))
  }

  override def reprToSeqNum(repr: PersistentRepr): Long = repr.sequenceNr

  def add(elems: immutable.Seq[PersistentRepr]): Unit =
    elems
      .groupBy(_.persistenceId)
      .foreach(gr ⇒ add(gr._1, gr._2))

}

trait PolicyOps[P <: ProcessingPolicy[_]] {

  protected val DefaultPolicy: P

  private lazy val _processingPolicy: AtomicReference[P] = new AtomicReference(DefaultPolicy)

  def currentPolicy = _processingPolicy.get()

  def setPolicy(policy: P) = _processingPolicy.set(policy)

  def compareAndSetWritingPolicy(previousPolicy: P, newPolicy: P) = _processingPolicy.compareAndSet(previousPolicy, newPolicy)

}

trait InMemStorageEmulator extends ReprInMemStorage with PolicyOps[JournalPolicy] {
  import InMemStorageEmulator._

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

}

object InMemStorageEmulator {

  trait JournalOperation

  case class Read(batch: immutable.Seq[Any]) extends JournalOperation

  case class Write(batch: immutable.Seq[Any]) extends JournalOperation

  case object ReadSeqNum extends JournalOperation

  case class Delete(toSeqNumber: Long) extends JournalOperation

  type JournalPolicy = ProcessingPolicy[JournalOperation]

  object JournalPolicies extends BasicPolicies[JournalOperation]

}

trait SnapshotInMemStorage extends HighestSeqNumberSupport[String, (SnapshotMetadata, Any)] {

  override def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[(SnapshotMetadata, Any)] = {
    val sn = reloadHighestSequenceNum(key)
    elems.zipWithIndex.map(p ⇒ (SnapshotMetadata(key, p._2 + sn), p._1))
  }

  override def reprToSeqNum(repr: (SnapshotMetadata, Any)): Long = repr._1.sequenceNr

}

trait SnapShotStorageEmulator extends SnapshotInMemStorage with PolicyOps[SnapshotPolicy] {
  import SnapShotStorageEmulator._

  override protected val DefaultPolicy = SnapshotPolicies.PassAll

  def tryAdd(meta: SnapshotMetadata, payload: Any): Unit = {
    currentPolicy.tryProcess(meta.persistenceId, Write(payload)) match {
      case ProcessingSuccess ⇒
        add(meta.persistenceId, (meta, payload))
        Success(())
      case StorageFailure(e) ⇒ throw e
      case Reject(e)         ⇒ throw e
    }
  }

  def tryRead(persistenceId: String, criteria: SnapshotSelectionCriteria): Option[SelectedSnapshot] = {
    val selectedSnapshot =
      read(persistenceId)
        .flatMap(_.reverseIterator.find(v ⇒ criteria.matches(v._1))
          .map(v ⇒ SelectedSnapshot(v._1, v._2)))
    currentPolicy.tryProcess(persistenceId, Read(selectedSnapshot.map(_.snapshot))) match {
      case ProcessingSuccess ⇒ selectedSnapshot
      case StorageFailure(e) ⇒ throw e
      case Reject(e)         ⇒ throw e
    }
  }

}

object SnapShotStorageEmulator {

  trait SnapshotOperation

  case class Read(snapshot: Option[Any]) extends SnapshotOperation

  case class Write(snapshot: Any) extends SnapshotOperation

  abstract class Delete extends SnapshotOperation

  case class DeleteByCriteria(persistenceId: String, criteria: SnapshotSelectionCriteria) extends Delete

  case class DeleteSnapshot(metadata: SnapshotMetadata) extends Delete

  type SnapshotPolicy = ProcessingPolicy[SnapshotOperation]

  object SnapshotPolicies extends BasicPolicies[SnapshotOperation]

}

class SnapShotStorageEmulatorExtension extends SnapShotStorageEmulator with Extension

object SnapShotStorageEmulatorExtension extends ExtensionId[SnapShotStorageEmulatorExtension] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): SnapShotStorageEmulatorExtension = new SnapShotStorageEmulatorExtension

  override def lookup(): ExtensionId[_ <: Extension] = SnapShotStorageEmulatorExtension
}

class InMemEmulatorExtension extends InMemStorageEmulator with Extension

object InMemStorageExtension extends ExtensionId[InMemEmulatorExtension] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem) = new InMemEmulatorExtension

  override def lookup() = InMemStorageExtension

}

