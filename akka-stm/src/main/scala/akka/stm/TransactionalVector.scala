/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

import scala.collection.immutable.Vector

import akka.actor.newUuid

/**
 * Transactional vector that implements the IndexedSeq interface with an underlying Ref and Vector.
 */
object TransactionalVector {
  def apply[T]() = new TransactionalVector[T]()

  def apply[T](elems: T*) = new TransactionalVector(Vector(elems: _*))
}

/**
 * Transactional vector that implements the IndexedSeq interface with an underlying Ref and Vector.
 *
 * TransactionalMap and TransactionalVector look like regular mutable datastructures, they even
 * implement the standard Scala 'Map' and 'IndexedSeq' interfaces, but they are implemented using
 * persistent datastructures and managed references under the hood. Therefore they are safe to use
 * in a concurrent environment through the STM. Underlying TransactionalVector is Vector, an immutable
 * sequence but with near constant time access and modification operations.
 *
 * From Scala you can use TVector as a shorter alias for TransactionalVector.
 */
class TransactionalVector[T](initialValue: Vector[T]) extends Transactional with IndexedSeq[T] {
  def this() = this(Vector[T]())

  val uuid = newUuid.toString

  private[this] val ref = Ref(initialValue)

  def clear = ref.swap(Vector[T]())

  def +(elem: T) = add(elem)

  def add(elem: T) = ref.swap(ref.get :+ elem)

  def get(index: Int): T = ref.get.apply(index)

  /**
   * Removes the <i>tail</i> element of this vector.
   */
  def pop = ref.swap(ref.get.dropRight(1))

  def update(index: Int, elem: T) = ref.swap(ref.get.updated(index, elem))

  def length: Int = ref.get.length

  def apply(index: Int): T = ref.get.apply(index)

  override def hashCode: Int = System.identityHashCode(this);

  override def equals(other: Any): Boolean =
    other.isInstanceOf[TransactionalVector[_]] &&
      other.hashCode == hashCode

  override def toString = if (Stm.activeTransaction) super.toString else "<TransactionalVector>"
}

