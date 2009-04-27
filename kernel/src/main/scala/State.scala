/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import se.scalablesolutions.akka.collection._
import scala.collection.mutable.HashMap

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
trait TransactionalMap[K, V] extends Transactional {
  def put(key: K, value: V)
  def remove(key: K)
  def get(key: K): V
  def contains(key: K): Boolean
  def elements: Iterator[(K, V)]
  def size: Int
  def clear
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

  override def begin = {
    changeSet.clear
  }

  override def put(key: K, value: V) = {
    changeSet += key -> value
  }

  override def remove(key: K) = {
    changeSet -= key
  }

  def getRange(start: Int, count: Int)
}

/**
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class InMemoryTransactionalMap[K, V] extends TransactionalMap[K, V] {
  protected[kernel] var state = new HashTrie[K, V]
  protected[kernel] var snapshot = state
 
  override def begin = snapshot = state
  override def commit = snapshot = state
  override def rollback = state = snapshot
  
  override def put(key: K, value: V) = state = state.update(key, value)
  override def get(key: K): V = state.get(key).getOrElse(throw new NoSuchElementException("No value for key [" + key + "]"))
  override def remove(key: K) = state = state - key
  override def contains(key: K): Boolean = state.contains(key)
  override def elements: Iterator[(K, V)] = state.elements
  override def size: Int = state.size
  override def clear = state = new HashTrie[K, V]
}

/**
 * Implements a persistent state based on the Cassandra distributed P2P key-value storage. 
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentTransactionalMap(val actorName: String) extends PersistentTransactionalMap[String, String] {
  override def begin = {}
  override def rollback = {}
  
  override def commit = {
    // FIXME: should use batch function once the bug is resolved
    for (entry <- changeSet) {
      val (key, value) = entry
      CassandraNode.insertActorStorageEntry(actorName, key, value)
    }
  }
  
  override def get(key: String): String = CassandraNode.getActorStorageEntryFor(actorName, key)
      .getOrElse(throw new NoSuchElementException("Could not find element for key [" + key + "]"))
  
  override def contains(key: String): Boolean = CassandraNode.getActorStorageEntryFor(actorName, key).isDefined
  
  override def size: Int = CassandraNode.getActorStorageSizeFor(actorName)
  
  override def clear = CassandraNode.removeActorStorageFor(actorName)
  
  override def getRange(start: Int, count: Int) = CassandraNode.getActorStorageRange(actorName, start, count)
  
  override def elements: Iterator[Tuple2[String, String]]  = { 
    new Iterator[Tuple2[String, String]] {
      private val originalList: List[Tuple2[String, String]] = CassandraNode.getActorStorageFor(actorName) 
      private var elements = originalList.reverse
  
      override def next: Tuple2[String, String]= synchronized {
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
abstract class TransactionalVector[T] extends Transactional {
  def add(elem: T)
  def get(index: Int): T
  def size: Int  
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

  override def begin = snapshot = state
  override def commit = snapshot = state
  override def rollback = state = snapshot

  override def add(elem: T) = state = state + elem
  override def get(index: Int): T = state(index)
  override def size: Int = state.size
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
