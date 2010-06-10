/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.util.UUID

import org.multiverse.api.GlobalStmInstance.getGlobalStmInstance

object TransactionalVector {
  def apply[T]() = new TransactionalVector[T]

  def apply[T](elems: T*) = new TransactionalVector(Some(Vector(elems: _*)))
}

/**
 * Implements an in-memory transactional Vector based on Clojure's PersistentVector.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalVector[T](initialOpt: Option[Vector[T]] = None) extends Transactional with IndexedSeq[T] {
  def this() = this(None) // Java compatibility

  val uuid = UUID.newUuid.toString

  private[this] val ref = new Ref(initialOpt.orElse(Some(EmptyVector)))

  def clear = ref.swap(EmptyVector)

  def +(elem: T) = add(elem)

  def add(elem: T) = ref.swap(ref.get.get + elem)

  def get(index: Int): T = ref.get.get.apply(index)

  /**
   * Removes the <i>tail</i> element of this vector.
   */
  def pop = ref.swap(ref.get.get.pop)

  def update(index: Int, elem: T) = ref.swap(ref.get.get.update(index, elem))

  def length: Int = ref.get.get.length

  def apply(index: Int): T = ref.get.get.apply(index)

  override def hashCode: Int = System.identityHashCode(this);

  override def equals(other: Any): Boolean =
    other.isInstanceOf[TransactionalVector[_]] &&
    other.hashCode == hashCode

  override def toString = if (outsideTransaction) "<TransactionalVector>" else super.toString

  def outsideTransaction =
    org.multiverse.api.ThreadLocalTransaction.getThreadLocalTransaction eq null
}
