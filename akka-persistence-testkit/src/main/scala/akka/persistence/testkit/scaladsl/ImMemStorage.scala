/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.util.concurrent.ConcurrentHashMap
import java.util.function.{ BiFunction, Consumer }

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.persistence.testkit.scaladsl.ProcessingPolicy.{ BasicPolicies, ProcessingSuccess, Reject, StorageFailure }
import akka.persistence.{ PersistentRepr, SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }

import scala.annotation.{ meta, tailrec }
import scala.collection.immutable
import scala.util.{ Failure, Success, Try }

trait InMemStorage[K, T] {

  private final val eventsMap: ConcurrentHashMap[K, Vector[T]] = new ConcurrentHashMap()

  def forEachKey(f: K ⇒ Unit) = eventsMap.forEachKey(Runtime.getRuntime.availableProcessors(), f)

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
   * Note! `elems` is call by name to preserve thread safety in case of use of mapping with `def mapAny`
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
    val sn = reloadHighestSequenceNum(key)
    elems.zipWithIndex.map(p ⇒ PersistentRepr(p._1, p._2 + sn, key))
  }

  override def reprToSeqNum(repr: PersistentRepr): Long = repr.sequenceNr

  def add(elems: immutable.Seq[PersistentRepr]): Unit =
    elems
      .groupBy(_.persistenceId)
      .foreach(gr ⇒ add(gr._1, gr._2))

}

trait InMemStorageEmulator extends ReprInMemStorage {
  import InMemStorageEmulator._

  @volatile
  private var writingPolicy: JournalPolicy = JournalPolicies.PassAll
  @volatile
  private var recoveryPolicy: JournalPolicy = JournalPolicies.PassAll

  /**
   *
   * @throws Exception from StorageFailure in the current writing policy
   */
  def tryAdd(elems: immutable.Seq[PersistentRepr]): Try[Unit] = {
    writingPolicy.tryProcess(elems.map(_.payload)) match {
      case ProcessingSuccess ⇒
        add(elems)
        Success(())
      case Reject(ex)         ⇒ Failure(ex)
      case StorageFailure(ex) ⇒ throw ex
    }
  }

  def tryRead(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long): immutable.Seq[PersistentRepr] = {
    val batch = read(persistenceId, fromSequenceNr, toSequenceNr, max)
    recoveryPolicy.tryProcess(batch) match {
      case ProcessingSuccess  ⇒ batch
      case Reject(ex)         ⇒ throw ex
      case StorageFailure(ex) ⇒ throw ex
    }
  }

  def setWritingPolicy(policy: JournalPolicy) = writingPolicy = policy

  def setRecoveryPolicy(policy: JournalPolicy) = recoveryPolicy = policy

}

object InMemStorageEmulator {

  type JournalPolicy = ProcessingPolicy[immutable.Seq[Any]]

  object JournalPolicies extends BasicPolicies[immutable.Seq[Any]]

}

trait SnapshotInMemStorage extends HighestSeqNumberSupport[String, (SnapshotMetadata, Any)] {

  override def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[(SnapshotMetadata, Any)] = {
    val sn = reloadHighestSequenceNum(key)
    elems.zipWithIndex.map(p ⇒ (SnapshotMetadata(key, p._2 + sn), p._1))
  }

  override def reprToSeqNum(repr: (SnapshotMetadata, Any)): Long = repr._1.sequenceNr

}

trait SnapShotStorageEmulator extends SnapshotInMemStorage {
  import SnapShotStorageEmulator._

  @volatile
  private var writingPolicy: SnapshotWritePolicy = SnapshotWritePolicies.PassAll
  @volatile
  private var recoveryPolicy: SnapshotReadPolicy = SnapshotReadPolicies.PassAll

  def tryAdd(meta: SnapshotMetadata, payload: Any): Unit = {
    writingPolicy.tryProcess(payload) match {
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
    recoveryPolicy.tryProcess(selectedSnapshot.map(_.snapshot)) match {
      case ProcessingSuccess ⇒ selectedSnapshot
      case StorageFailure(e) ⇒ throw e
      case Reject(e)         ⇒ throw e
    }
  }

  def setWritingPolicy(policy: SnapshotWritePolicy) = writingPolicy = policy

  def setRecoveryPolicy(policy: SnapshotReadPolicy) = recoveryPolicy = policy

}

object SnapShotStorageEmulator {

  type SnapshotWritePolicy = ProcessingPolicy[Any]

  type SnapshotReadPolicy = ProcessingPolicy[Option[Any]]

  object SnapshotWritePolicies extends BasicPolicies[Any]

  object SnapshotReadPolicies extends BasicPolicies[Option[Any]]

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

