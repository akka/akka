/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable

import akka.actor.Extension
import akka.annotation.InternalApi
import akka.persistence.testkit.ProcessingPolicy

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

  import scala.math._

  private final val eventsMap: ConcurrentHashMap[K, (Long, Vector[InternalRepr])] =
    new ConcurrentHashMap()

  def reprToSeqNum(repr: R): Long

  def findMany(key: K, fromInclusive: Int, maxNum: Int): Option[Vector[R]] =
    read(key).flatMap(
      value =>
        if (value.size > fromInclusive)
          Some(value.drop(fromInclusive).take(maxNum))
        else None)

  def findOneByIndex(key: K, index: Int): Option[R] =
    Option(eventsMap.get(key))
      .flatMap {
        case (_, value) => if (value.size > index) Some(value(index)) else None
      }
      .map(toRepr)

  def add(key: K, p: R): Unit =
    add(key, List(p))

  /**
   * Adds elements ordered by seqnum, sets new seqnum as max(old, max(newElemsSeqNums)))
   */
  def add(key: K, elems: immutable.Seq[R]): Unit =
    updateOrSetNew(key, v => v ++ elems)

  /**
   * Deletes elements preserving highest sequence number.
   */
  def delete(key: K, needsToBeDeleted: R => Boolean): Vector[R] =
    updateOrSetNew(key, v => v.filterNot(needsToBeDeleted))

  /**
   * Sets new elements returned by updater ordered by seqnum. Sets new seqnum as max(old, max(newElemsFromUpdaterSeqNums))
   */
  def updateOrSetNew(key: K, updater: Vector[R] => Vector[R]): Vector[R] =
    eventsMap
      .compute(
        key,
        (_: K, value: (Long, Vector[InternalRepr])) => {
          val (sn, elems) = if (value != null) value else (0L, Vector.empty)
          val upd = updater(elems.map(toRepr)).sortBy(reprToSeqNum)
          (max(getLastSeqNumber(upd), sn), upd.map(toInternal))
        })
      ._2
      .map(toRepr)

  def read(key: K): Option[Vector[R]] =
    Option(eventsMap.get(key)).map(_._2.map(toRepr))

  def clearAll(): Unit =
    eventsMap.clear()

  /**
   * Removes key and the whole value including seqnum.
   */
  def removeKey(key: K): Vector[R] =
    Option(eventsMap.remove(key)).map(_._2).getOrElse(Vector.empty).map(toRepr)

  /**
   * Reads elems within the range of seqnums.
   */
  def read(key: K, fromInclusive: Long, toInclusive: Long, maxNumber: Long): immutable.Seq[R] =
    read(key)
      .getOrElse(Vector.empty)
      .dropWhile(reprToSeqNum(_) < fromInclusive)
      // we dont need to read highestSeqNumber because it will in any case stop at it if toInclusive > highestSeqNumber
      .takeWhile(reprToSeqNum(_) <= toInclusive)
      .take(if (maxNumber > Int.MaxValue) Int.MaxValue else maxNumber.toInt)

  def removePreservingSeqNumber(key: K): Unit =
    updateOrSetNew(key, _ => Vector.empty)

  def getHighestSeqNumber(key: K): Long =
    Option(eventsMap.get(key)).map(_._1).getOrElse(0L)

  def deleteToSeqNumber(key: K, toSeqNumberInclusive: Long): Unit =
    updateOrSetNew(key, value => {
      value.dropWhile(reprToSeqNum(_) <= toSeqNumberInclusive)
    })

  def clearAllPreservingSeqNumbers(): Unit =
    eventsMap.forEachKey(1, removePreservingSeqNumber)

  private def getLastSeqNumber(elems: immutable.Seq[R]): Long =
    elems.lastOption.map(reprToSeqNum).getOrElse(0L)

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

  def currentPolicy: Policy = _processingPolicy.get()

  def setPolicy(policy: Policy): Unit = _processingPolicy.set(policy)

  def resetPolicy(): Unit = setPolicy(DefaultPolicy)

}

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] trait TestKitStorage[P, R] extends InMemStorage[String, R] with PolicyOps[P] with Extension
