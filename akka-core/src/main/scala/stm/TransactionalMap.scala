/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import scala.collection.immutable.HashMap

import se.scalablesolutions.akka.util.UUID

import org.multiverse.api.ThreadLocalTransaction.getThreadLocalTransaction

object TransactionalMap {
  def apply[K, V]() = new TransactionalMap[K, V]()

  def apply[K, V](pairs: (K, V)*) = new TransactionalMap(HashMap(pairs: _*))
}

/**
 * Transactional map that implements the mutable map interface with an underlying ref and hash map.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalMap[K, V](initialValue: HashMap[K, V]) extends Transactional with scala.collection.mutable.Map[K, V] {
  def this() = this(HashMap[K, V]())

  val uuid = UUID.newUuid.toString

  private[this] val ref = Ref(initialValue)

  def -=(key: K) = {
    remove(key)
    this
  }

  def +=(key: K, value: V) = put(key, value)

  def +=(kv: (K, V)) = {
    put(kv._1,kv._2)
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

  override def toString = if (outsideTransaction) "<TransactionalMap>" else super.toString

  def outsideTransaction = getThreadLocalTransaction eq null
}
