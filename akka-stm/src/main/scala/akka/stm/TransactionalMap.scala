/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

import scala.collection.immutable.HashMap

import akka.actor.{ newUuid }

/**
 * Transactional map that implements the mutable Map interface with an underlying Ref and HashMap.
 */
object TransactionalMap {
  def apply[K, V]() = new TransactionalMap[K, V]()

  def apply[K, V](pairs: (K, V)*) = new TransactionalMap(HashMap(pairs: _*))
}

/**
 * Transactional map that implements the mutable Map interface with an underlying Ref and HashMap.
 *
 * TransactionalMap and TransactionalVector look like regular mutable datastructures, they even
 * implement the standard Scala 'Map' and 'IndexedSeq' interfaces, but they are implemented using
 * persistent datastructures and managed references under the hood. Therefore they are safe to use
 * in a concurrent environment through the STM. Underlying TransactionalMap is HashMap, an immutable
 * Map but with near constant time access and modification operations.
 *
 * From Scala you can use TMap as a shorter alias for TransactionalMap.
 */
class TransactionalMap[K, V](initialValue: HashMap[K, V]) extends Transactional with scala.collection.mutable.Map[K, V] {
  def this() = this(HashMap[K, V]())

  val uuid = newUuid.toString

  private[this] val ref = Ref(initialValue)

  def -=(key: K) = {
    remove(key)
    this
  }

  def +=(key: K, value: V) = put(key, value)

  def +=(kv: (K, V)) = {
    put(kv._1, kv._2)
    this
  }

  override def remove(key: K) = {
    val map = ref.get
    val oldValue = map.get(key)
    ref.swap(ref.get - key)
    oldValue
  }

  def get(key: K): Option[V] = ref.get.get(key)

  override def put(key: K, value: V): Option[V] = {
    val map = ref.get
    val oldValue = map.get(key)
    ref.swap(map.updated(key, value))
    oldValue
  }

  override def update(key: K, value: V) = {
    val map = ref.get
    val oldValue = map.get(key)
    ref.swap(map.updated(key, value))
  }

  def iterator = ref.get.iterator

  override def elements: Iterator[(K, V)] = ref.get.iterator

  override def contains(key: K): Boolean = ref.get.contains(key)

  override def clear = ref.swap(HashMap[K, V]())

  override def size: Int = ref.get.size

  override def hashCode: Int = System.identityHashCode(this);

  override def equals(other: Any): Boolean =
    other.isInstanceOf[TransactionalMap[_, _]] &&
      other.hashCode == hashCode

  override def toString = if (Stm.activeTransaction) super.toString else "<TransactionalMap>"
}
