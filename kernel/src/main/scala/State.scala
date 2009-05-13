/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import se.scalablesolutions.akka.collection._
import scala.collection.mutable.HashMap

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Transactional {
  private[kernel] def begin
  private[kernel] def commit
  private[kernel] def rollback
}

/**
 * Base trait for all state implementations (persistent or in-memory).
 * 
 * TODO: Make this class inherit scala.collection.mutable.Map and/or java.util.Map
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait TransactionalMap[K, V] extends Transactional with scala.collection.mutable.Map[K, V] {
  def remove(key: K)   
}

/**
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class InMemoryTransactionalMap[K, V] extends TransactionalMap[K, V] {
  protected[kernel] var state = new HashTrie[K, V]
  protected[kernel] var snapshot = state

  // ---- For Transactional ----
  override def begin = snapshot = state
  override def commit = snapshot = state
  override def rollback = state = snapshot
  
  // ---- Overriding scala.collection.mutable.Map behavior ----
  override def contains(key: K): Boolean = state.contains(key)
  override def clear = state = new HashTrie[K, V]
  override def size: Int = state.size

  // ---- For scala.collection.mutable.Map ----
  override def remove(key: K) = state = state - key
  override def elements: Iterator[(K, V)] = state.elements
  override def get(key: K): Option[V] = state.get(key)
  override def put(key: K, value: V): Option[V] = {
    val oldValue = state.get(key)
    state = state.update(key, value)
    oldValue
  }
  override def -=(key: K) = remove(key)
  override def update(key: K, value: V) = put(key, value)
}

/**
 * Base class for all persistent state implementations should extend.
 * Implements a Unit of Work, records changes into a change set.
 * 
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class PersistentTransactionalMap[K, V] extends TransactionalMap[K, V] {
  protected[kernel] val changeSet = new HashMap[K, V]
  
  def getRange(start: Int, count: Int)

  // ---- For Transactional ----
  override def begin = changeSet.clear
  override def rollback = {}

  // ---- For scala.collection.mutable.Map ----
  override def put(key: K, value: V): Option[V] = {
    changeSet += key -> value
    None // always return None to speed up writes (else need to go to DB to get
  }
  override def remove(key: K) = changeSet -= key
  override def -=(key: K) = remove(key)
  override def update(key: K, value: V) = put(key, value)
}

/**
 * Implements a persistent state based on the Cassandra distributed P2P key-value storage. 
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentTransactionalMap(actorNameInstance: AnyRef)
    extends PersistentTransactionalMap[String, AnyRef] {
  
  val actorName = actorNameInstance.getClass.getName

  override def getRange(start: Int, count: Int) = CassandraNode.getActorStorageRange(actorName, start, count)

  // ---- For Transactional ----
  override def commit = {
    // FIXME: should use batch function once the bug is resolved
    for (entry <- changeSet) {
      val (key, value) = entry
      CassandraNode.insertActorStorageEntry(actorName, key, value)
    }
  }
  
  // ---- Overriding scala.collection.mutable.Map behavior ----
  override def clear = CassandraNode.removeActorStorageFor(actorName)
  override def contains(key: String): Boolean = CassandraNode.getActorStorageEntryFor(actorName, key).isDefined
  override def size: Int = CassandraNode.getActorStorageSizeFor(actorName)
 
  // ---- For scala.collection.mutable.Map ----
  override def get(key: String): Option[AnyRef] = CassandraNode.getActorStorageEntryFor(actorName, key)
  override def elements: Iterator[Tuple2[String, AnyRef]]  = {
    new Iterator[Tuple2[String, AnyRef]] {
      private val originalList: List[Tuple2[String, AnyRef]] = CassandraNode.getActorStorageFor(actorName) 
      private var elements = originalList.reverse
      override def next: Tuple2[String, AnyRef]= synchronized {
        val element = elements.head
        elements = elements.tail
        element
      }      
      override def hasNext: Boolean = synchronized { !elements.isEmpty }
    }
  }
}

/**
 * TODO: extend scala.Seq
 * Base for all transactional vector implementations.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TransactionalVector[T] extends Transactional with RandomAccessSeq[T] {
  def add(elem: T)
  def get(index: Int): T
}

/**
 * Implements an in-memory transactional vector.
 * 
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class InMemoryTransactionalVector[T] extends TransactionalVector[T] {
  private[kernel] var state: Vector[T] = EmptyVector
  private[kernel] var snapshot = state

  override def add(elem: T) = state = state + elem
  override def get(index: Int): T = state(index)

  // ---- For Transactional ----
  override def begin = snapshot = state
  override def commit = snapshot = state
  override def rollback = state = snapshot

  // ---- For Seq ----
  def length: Int = state.length
  def apply(index: Int): T = state(index)
  override def elements: Iterator[T] = state.elements
  override def toList: List[T] = state.toList
}

/**
 * Implements a transactional reference.
 * 
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalRef[T] extends Transactional {
  private[kernel] var ref: Option[T] = None
  private[kernel] var snapshot: Option[T] = None

  override def begin = if (ref.isDefined) snapshot = Some(ref.get)
  override def commit = if (ref.isDefined) snapshot = Some(ref.get)
  override def rollback = if (snapshot.isDefined) ref = Some(snapshot.get)

  def swap(elem: T) = ref = Some(elem)
  def get: Option[T] = ref
  def getOrElse(default: => T): T = ref.getOrElse(default)
  def isDefined: Boolean= ref.isDefined
}
