/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.util.UUID

import org.multiverse.transactional.refs.BasicRef

/**
 * Ref
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Ref {
  def apply[T]() = new Ref[T]()

  def apply[T](initialValue: T) = new Ref[T](initialValue)

  /**
   * An implicit conversion that converts a Ref to an Iterable value.
   */
  implicit def ref2Iterable[T](ref: Ref[T]): Iterable[T] = ref.toList
}

/**
 * Transactional managed reference.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Ref[T](initialValue: T) extends BasicRef[T](initialValue) with Transactional {
  self =>

  def this() = this(null.asInstanceOf[T])

  val uuid = UUID.newUuid.toString

  def swap(elem: T) = set(elem)

  def alter(f: T => T): T = {
    val value = f(this.get)
    set(value)
    value
  }

  def opt: Option[T] = Option(this.get)

  def getOrWait: T = getOrAwait

  def getOrElse(default: => T): T =
    if (isNull) default else this.get

  def isDefined: Boolean = !isNull

  def isEmpty: Boolean = isNull

  def map[B](f: T => B): Ref[B] =
    if (isEmpty) Ref[B] else Ref(f(this.get))

  def flatMap[B](f: T => Ref[B]): Ref[B] =
    if (isEmpty) Ref[B] else f(this.get)

  def filter(p: T => Boolean): Ref[T] =
    if (isDefined && p(this.get)) Ref(this.get) else Ref[T]

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

  def foreach[U](f: T => U): Unit =
    if (isDefined) f(this.get)

  def elements: Iterator[T] =
    if (isEmpty) Iterator.empty else Iterator(this.get)

  def toList: List[T] =
    if (isEmpty) List() else List(this.get)

  def toRight[X](left: => X) =
    if (isEmpty) Left(left) else Right(this.get)

  def toLeft[X](right: => X) =
    if (isEmpty) Right(right) else Left(this.get)
}
