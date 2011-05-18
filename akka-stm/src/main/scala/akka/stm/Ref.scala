/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

import akka.actor.{ newUuid, Uuid }

import org.multiverse.transactional.refs.BasicRef

/**
 * Common trait for all the transactional objects.
 */
trait Transactional extends Serializable {
  val uuid: String
}

/**
 * Transactional managed reference. See the companion class for more information.
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
 * Refs (transactional references) are mutable references to values and through
 * the STM allow the safe sharing of mutable data. Refs separate identity from value.
 * To ensure safety the value stored in a Ref should be immutable (they can also
 * contain refs themselves). The value referenced by a Ref can only be accessed
 * or swapped within a transaction. If a transaction is not available, the call will
 * be executed in its own transaction.
 * <br/><br/>
 *
 * Creating a Ref ''(Scala)''
 *
 * {{{
 * import akka.stm._
 *
 * // giving an initial value
 * val ref = Ref(0)
 *
 * // specifying a type but no initial value
 * val ref = Ref[Int]
 * }}}
 * <br/>
 *
 * Creating a Ref ''(Java)''
 *
 * {{{
 * import akka.stm.*;
 *
 * // giving an initial value
 * final Ref<Integer> ref = new Ref<Integer>(0);
 *
 * // specifying a type but no initial value
 * final Ref<Integer> ref = new Ref<Integer>();
 * }}}
 */
class Ref[T](initialValue: T) extends BasicRef[T](initialValue) with Transactional {
  self ⇒

  def this() = this(null.asInstanceOf[T])

  val uuid = newUuid.toString

  def apply() = get

  def update(newValue: T) = set(newValue)

  def swap(newValue: T) = set(newValue)

  def alter(f: T ⇒ T): T = {
    val value = f(get)
    set(value)
    value
  }

  def opt: Option[T] = Option(get)

  def getOrWait: T = getOrAwait

  def getOrElse(default: ⇒ T): T =
    if (isNull) default else get

  def isDefined: Boolean = !isNull

  def isEmpty: Boolean = isNull

  def map[B](f: T ⇒ B): Ref[B] =
    if (isEmpty) Ref[B] else Ref(f(get))

  def flatMap[B](f: T ⇒ Ref[B]): Ref[B] =
    if (isEmpty) Ref[B] else f(get)

  def filter(p: T ⇒ Boolean): Ref[T] =
    if (isDefined && p(get)) Ref(get) else Ref[T]

  /**
   * Necessary to keep from being implicitly converted to Iterable in for comprehensions.
   */
  def withFilter(p: T ⇒ Boolean): WithFilter = new WithFilter(p)

  class WithFilter(p: T ⇒ Boolean) {
    def map[B](f: T ⇒ B): Ref[B] = self filter p map f
    def flatMap[B](f: T ⇒ Ref[B]): Ref[B] = self filter p flatMap f
    def foreach[U](f: T ⇒ U): Unit = self filter p foreach f
    def withFilter(q: T ⇒ Boolean): WithFilter = new WithFilter(x ⇒ p(x) && q(x))
  }

  def foreach[U](f: T ⇒ U): Unit =
    if (isDefined) f(get)

  def elements: Iterator[T] =
    if (isEmpty) Iterator.empty else Iterator(get)

  def toList: List[T] =
    if (isEmpty) List() else List(get)

  def toRight[X](left: ⇒ X) =
    if (isEmpty) Left(left) else Right(get)

  def toLeft[X](right: ⇒ X) =
    if (isEmpty) Right(right) else Left(get)
}
