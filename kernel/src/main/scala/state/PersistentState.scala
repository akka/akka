/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.state

import kernel.stm.{Ref, TransactionManagement}
import akka.collection._

import org.codehaus.aspectwerkz.proxy.Uuid

import scala.collection.mutable.{ArrayBuffer, HashMap}

import org.multiverse.utils.TransactionThreadLocal._

/**
 * Base class for all persistent transactional map implementations should extend.
 * Implements a Unit of Work, records changes into a change set.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class PersistentTransactionalMap[K, V] extends TransactionalMap[K, V] {

  // FIXME: need to handle remove in another changeSet
  protected[kernel] val changeSet = new HashMap[K, V]

  def getRange(start: Option[AnyRef], count: Int)

  // ---- For scala.collection.mutable.Map ----
  override def put(key: K, value: V): Option[V] = {
    verifyTransaction
    changeSet += key -> value
    None // always return None to speed up writes (else need to go to DB to get
  }

  override def -=(key: K) = remove(key)

  override def update(key: K, value: V) = put(key, value)
}

/**
 * Implementation of <tt>PersistentTransactionalMap</tt> for every concrete 
 * storage will have the same workflow. This abstracts the workflow.
 *
 * Subclasses just need to provide the actual concrete instance for the
 * abstract val <tt>storage</tt>.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TemplatePersistentTransactionalMap extends PersistentTransactionalMap[AnyRef, AnyRef] {

  // to be concretized in subclasses
  val storage: MapStorage

  override def remove(key: AnyRef) = {
    if (changeSet.contains(key)) changeSet -= key
    else storage.removeMapStorageFor(uuid, key)
  }

  override def getRange(start: Option[AnyRef], count: Int) =
    getRange(start, None, count)

  def getRange(start: Option[AnyRef], finish: Option[AnyRef], count: Int) = {
    try {
      storage.getMapStorageRangeFor(uuid, start, finish, count)
    } catch {
      case e: Exception => Nil
    }
  }

  // ---- For Transactional ----
  override def commit = {
    storage.insertMapStorageEntriesFor(uuid, changeSet.toList)
    changeSet.clear
  }

  // ---- Overriding scala.collection.mutable.Map behavior ----
  override def clear = {
    try {
      storage.removeMapStorageFor(uuid)
    } catch {
      case e: Exception => {}
    }
  }

  override def contains(key: AnyRef): Boolean = {
    try {
      verifyTransaction
      storage.getMapStorageEntryFor(uuid, key).isDefined
    } catch {
      case e: Exception => false
    }
  }

  override def size: Int = {
    verifyTransaction
    try {
      storage.getMapStorageSizeFor(uuid)
    } catch {
      case e: Exception => 0
    }
  }

  // ---- For scala.collection.mutable.Map ----
  override def get(key: AnyRef): Option[AnyRef] = {
    verifyTransaction
   // if (changeSet.contains(key)) changeSet.get(key)
   // else {
      val result = try {
        storage.getMapStorageEntryFor(uuid, key)
      } catch {
        case e: Exception => None
      }
      result      
    //}
  }
  
  override def elements: Iterator[Tuple2[AnyRef, AnyRef]]  = {
    //verifyTransaction
    new Iterator[Tuple2[AnyRef, AnyRef]] {
      private val originalList: List[Tuple2[AnyRef, AnyRef]] = try {
        storage.getMapStorageFor(uuid)
      } catch {
        case e: Throwable => Nil
      }
      private var elements = originalList.reverse
      override def next: Tuple2[AnyRef, AnyRef]= synchronized {
        val element = elements.head
        elements = elements.tail
        element
      }
      override def hasNext: Boolean = synchronized { !elements.isEmpty }
    }
  }
}


/**
 * Implements a persistent transactional map based on the Cassandra distributed P2P key-value storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class CassandraPersistentTransactionalMap extends TemplatePersistentTransactionalMap {
  val storage = CassandraStorage
}

/**
 * Implements a persistent transactional map based on the MongoDB distributed P2P key-value storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class MongoPersistentTransactionalMap extends TemplatePersistentTransactionalMap {
  val storage = MongoStorage
}

/**
 * Base class for all persistent transactional vector implementations should extend.
 * Implements a Unit of Work, records changes into a change set.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class PersistentTransactionalVector[T] extends TransactionalVector[T] {

  // FIXME: need to handle remove in another changeSet
  protected[kernel] val changeSet = new ArrayBuffer[T]

  // ---- For TransactionalVector ----
  override def add(value: T) = {
    verifyTransaction
    changeSet += value
  }
}

/**
 * Implements a template for a concrete persistent transactional vector based storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
abstract class TemplatePersistentTransactionalVector extends PersistentTransactionalVector[AnyRef] {

  val storage: VectorStorage

  // ---- For TransactionalVector ----
  override def get(index: Int): AnyRef = {
    verifyTransaction
    if (changeSet.size > index) changeSet(index)
    else storage.getVectorStorageEntryFor(uuid, index)
  }

  override def getRange(start: Int, count: Int): List[AnyRef] =
    getRange(Some(start), None, count)
  
  def getRange(start: Option[Int], finish: Option[Int], count: Int): List[AnyRef] = {
    verifyTransaction
    storage.getVectorStorageRangeFor(uuid, start, finish, count)
  }

  override def length: Int = {
    verifyTransaction
    storage.getVectorStorageSizeFor(uuid)
  }

  override def apply(index: Int): AnyRef = get(index)

  override def first: AnyRef = get(0)

  override def last: AnyRef = {
    verifyTransaction
    val l = length
    if (l == 0) throw new NoSuchElementException("Vector is empty")
    get(length - 1)
  }

  override def commit = {
    // FIXME: should use batch function once the bug is resolved
    for (element <- changeSet) storage.insertVectorStorageEntryFor(uuid, element)
    changeSet.clear
  }
}

/**
 * Implements a persistent transactional vector based on the Cassandra distributed P2P key-value storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debaissh Ghosh</a>
 */
class CassandraPersistentTransactionalVector extends TemplatePersistentTransactionalVector {
  val storage = CassandraStorage
}

/**
 * Implements a persistent transactional vector based on the MongoDB distributed P2P key-value storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debaissh Ghosh</a>
 */
class MongoPersistentTransactionalVector extends TemplatePersistentTransactionalVector {
  val storage = MongoStorage
}

/**
 * Implements a transactional reference.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentTransactionalRef extends TransactionalRef[AnyRef] {
  override def commit = if (isDefined) {
    CassandraStorage.insertRefStorageFor(uuid, ref.get)
    ref.clear
  }

  override def get: Option[AnyRef] = {
    verifyTransaction
    if (isDefined) super.get
    else CassandraStorage.getRefStorageFor(uuid)
  }

  override def isDefined: Boolean = get.isDefined

  override def getOrElse(default: => AnyRef): AnyRef = {
    val ref = get
    if (ref.isDefined) ref.get
    else default
  }
}
