/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.persistence.{ PersistentRepr, SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }

import scala.collection.immutable
import scala.util.{ Failure, Success, Try }

trait InMemStorage[K, T] {

  private final val eventsMap: ConcurrentHashMap[K, Vector[T]] = new ConcurrentHashMap()

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

  def read(key: K): Option[Vector[T]] = Option(eventsMap.get(key))

  def clearAll() = eventsMap.clear()

  def removeKey(key: K) = eventsMap.remove(key)

  import java.util.{ function ⇒ jf }

  import scala.language.implicitConversions

  private implicit def scalaFun1ToJava[T, R](f: T ⇒ R): jf.Function[T, R] = new jf.Function[T, R] {
    override def apply(t: T): R = f(t)
  }

  private implicit def scalaFun2ToJava[T, M, R](f: (T, M) ⇒ R): jf.BiFunction[T, M, R] = new BiFunction[T, M, R] {
    override def apply(t: T, u: M): R = f(t, u)
  }

}

trait ReprInMemStorage extends InMemStorage[String, PersistentRepr] {

  override def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[PersistentRepr] = {
    val sn = readHighestSequenceNum(key)
    elems.zipWithIndex.map(p ⇒ PersistentRepr(p._1, p._2 + sn, key))
  }

  def add(elems: immutable.Seq[PersistentRepr]): Unit =
    elems
      .groupBy(_.persistenceId)
      .foreach(gr ⇒ add(gr._1, gr._2))

  def deleteToSeqNumber(persistenceId: String, toSeqNumberInclusive: Long): Unit =
    delete(persistenceId, _.sequenceNr <= toSeqNumberInclusive)

  def read(persistenceId: String, fromInclusive: Long, toInclusive: Long, maxNumber: Long): immutable.Seq[PersistentRepr] =
    read(persistenceId).getOrElse(Vector.empty)
      .dropWhile(_.sequenceNr < fromInclusive)
      //we dont need to read highestSeqNumber because it will in any case stop at it if toInclusive > highestSeqNumber
      .takeWhile(_.sequenceNr <= toInclusive)
      .take(maxNumber.toInt)

  def readHighestSequenceNum(persistenceId: String): Long =
    read(persistenceId)
      .flatMap(_.lastOption)
      .map(_.sequenceNr)
      .getOrElse(0L)

}

trait InMemStorageEmulator extends ReprInMemStorage {

  import ProcessingPolicy._

  @volatile
  private var writingPolicy: ProcessingPolicy = ProcessingPolicy.PassAll
  @volatile
  private var recoveryPolicy: ProcessingPolicy = ProcessingPolicy.PassAll

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

  def setWritingPolicy(policy: ProcessingPolicy) = writingPolicy = policy

  def setRecoveryPolicy(policy: ProcessingPolicy) = recoveryPolicy = policy

}

trait SnapshotInMemStorage extends InMemStorage[String, (SnapshotMetadata, Any)] {

  override def mapAny(key: String, elems: immutable.Seq[Any]): immutable.Seq[(SnapshotMetadata, Any)] = {
    val sn = readHighestSequenceNum(key)
    elems.zipWithIndex.map(p ⇒ (SnapshotMetadata(key, p._2 + sn), p._1))
  }

  def readHighestSequenceNum(persistenceId: String): Long =
    read(persistenceId)
      .flatMap(_.lastOption)
      .map(_._1.sequenceNr)
      .getOrElse(0L)

}

trait SnapShotStorageEmulator extends SnapshotInMemStorage {

  def tryAdd(meta: SnapshotMetadata, payload: Any): Try[Unit] = ???

  def tryRead(persistenceId: String, criteria: SnapshotSelectionCriteria): Option[SelectedSnapshot] = ???

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

