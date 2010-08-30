/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import scala.collection.immutable.Vector

import se.scalablesolutions.akka.util.UUID

import org.multiverse.api.ThreadLocalTransaction.getThreadLocalTransaction

object TransactionalVector {
  def apply[T]() = new TransactionalVector[T]()

  def apply[T](elems: T*) = new TransactionalVector(Vector(elems: _*))
}

/**
 * Transactional vector that implements the indexed seq interface with an underlying ref and vector.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalVector[T](initialValue: Vector[T]) extends Transactional with IndexedSeq[T] {
  def this() = this(Vector[T]())

  val uuid = UUID.newUuid.toString

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

  override def toString = if (outsideTransaction) "<TransactionalVector>" else super.toString

  def outsideTransaction =
    org.multiverse.api.ThreadLocalTransaction.getThreadLocalTransaction eq null
}

