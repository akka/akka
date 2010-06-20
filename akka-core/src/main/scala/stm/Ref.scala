/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.util.UUID

import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance

object RefFactory {
  private val factory = getGlobalStmInstance.getProgrammaticRefFactoryBuilder.build

  def createRef[T] = factory.atomicCreateRef[T]()

  def createRef[T](value: T) = factory.atomicCreateRef(value)
}

/**
 * Ref.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Ref {
  def apply[T]() = new Ref[T]

  def apply[T](initialValue: T) = new Ref[T](Some(initialValue))

  /**
   * An implicit conversion that converts a Ref to an Iterable value.
   */
  implicit def ref2Iterable[T](ref: Ref[T]): Iterable[T] = ref.toList
}

/**
 * Implements a transactional managed reference.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Ref[T](initialOpt: Option[T] = None) extends Transactional {
  self =>

  def this() = this(None) // Java compatibility

  import org.multiverse.api.ThreadLocalTransaction._

  val uuid = UUID.newUuid.toString

  private[this] val ref = {
    if (initialOpt.isDefined) RefFactory.createRef(initialOpt.get)
    else RefFactory.createRef[T]
  }

  def swap(elem: T) = {
    ensureIsInTransaction
    ref.set(elem)
  }

  def alter(f: T => T): T = {
    ensureIsInTransaction
    ensureNotNull
    ref.set(f(ref.get))
    ref.get
  }

  def get: Option[T] = {
    ensureIsInTransaction
    if (ref.isNull) None
    else Some(ref.get)
  }

  def getOrWait: T = {
    ensureIsInTransaction
    ref.getOrAwait
  }

  def getOrElse(default: => T): T = {
    ensureIsInTransaction
    if (ref.isNull) default
    else ref.get
  }

  def isDefined: Boolean = {
    ensureIsInTransaction
    !ref.isNull
  }

  def isEmpty: Boolean = {
    ensureIsInTransaction
    ref.isNull
  }

  def map[B](f: T => B): Ref[B] = {
    ensureIsInTransaction
    if (isEmpty) Ref[B] else Ref(f(ref.get))
  }

  def flatMap[B](f: T => Ref[B]): Ref[B] = {
    ensureIsInTransaction
    if (isEmpty) Ref[B] else f(ref.get)
  }

  def filter(p: T => Boolean): Ref[T] = {
    ensureIsInTransaction
    if (isDefined && p(ref.get)) Ref(ref.get) else Ref[T]
  }

  /**
   * Necessary to keep from being implicitly converted to Iterable in for comprehensions.
   */
  def withFilter(p: T => Boolean): WithFilter = new WithFilter(p)

  class WithFilter(p: T => Boolean) {
    def map[B](f: T => B): Ref[B] = self filter p map f
    def flatMap[B](f: T => Ref[B]): Ref[B] = self filter p flatMap f
    def foreach[U](f: T => U): Unit = self filter p foreach f
    def withFilter(q: T => Boolean): WithFilter = new WithFilter(x => p(x) && q(x))
  }

  def foreach[U](f: T => U): Unit = {
    ensureIsInTransaction
    if (isDefined) f(ref.get)
  }

  def elements: Iterator[T] = {
    ensureIsInTransaction
    if (isEmpty) Iterator.empty else Iterator(ref.get)
  }

  def toList: List[T] = {
    ensureIsInTransaction
    if (isEmpty) List() else List(ref.get)
  }

  def toRight[X](left: => X) = {
    ensureIsInTransaction
    if (isEmpty) Left(left) else Right(ref.get)
  }

  def toLeft[X](right: => X) = {
    ensureIsInTransaction
    if (isEmpty) Right(right) else Left(ref.get)
  }

  private def ensureIsInTransaction =
   if (getThreadLocalTransaction eq null) throw new NoTransactionInScopeException

  private def ensureNotNull =
    if (ref.isNull) throw new RuntimeException("Cannot alter Ref's value when it is null")
}
