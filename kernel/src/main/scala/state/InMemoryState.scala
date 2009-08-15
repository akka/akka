/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.state

import kernel.stm.{Ref, TransactionManagement}
import akka.collection._

import org.codehaus.aspectwerkz.proxy.Uuid

import scala.collection.mutable.{ArrayBuffer, HashMap}

/**
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class InMemoryTransactionalMap[K, V] extends TransactionalMap[K, V] {
  protected[this] val ref = new TransactionalRef[HashTrie[K, V]](new HashTrie[K, V])

  // ---- Overriding scala.collection.mutable.Map behavior ----
  override def contains(key: K): Boolean = ref.get.get.contains(key)
  override def clear = ref.swap(new HashTrie[K, V])
  override def size: Int = ref.get.get.size

  // ---- For scala.collection.mutable.Map ----
  override def remove(key: K) = ref.swap(ref.get.get - key)
  override def elements: Iterator[(K, V)] = ref.get.get.elements
  override def get(key: K): Option[V] = ref.get.get.get(key)
  override def put(key: K, value: V): Option[V] = {
    val map = ref.get.get
    val oldValue = map.get(key)
    ref.swap(map.update(key, value))
    oldValue
  }
  override def -=(key: K) = remove(key)
  override def update(key: K, value: V) = put(key, value)
}

/**
 * Implements an in-memory transactional vector.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class InMemoryTransactionalVector[T] extends TransactionalVector[T] {
  private[kernel] val ref = new TransactionalRef[Vector[T]](EmptyVector) 

  def add(elem: T) = ref.swap(ref.get.get + elem)
  def get(index: Int): T = ref.get.get.apply(index)
  def getRange(start: Int, count: Int): List[T] = ref.get.get.slice(start, count).toList.asInstanceOf[List[T]]

  // ---- For Seq ----
  def length: Int = ref.get.get.length
  def apply(index: Int): T = ref.get.get.apply(index)
  override def elements: Iterator[T] = ref.get.get.elements
  override def toList: List[T] = ref.get.get.toList
}

