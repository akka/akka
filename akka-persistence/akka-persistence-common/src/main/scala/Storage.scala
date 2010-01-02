/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.stm.TransactionManagement.currentTransaction
import se.scalablesolutions.akka.collection._
import se.scalablesolutions.akka.util.Logging

import org.codehaus.aspectwerkz.proxy.Uuid

class NoTransactionInScopeException extends RuntimeException

/**
 * Example Scala usage.
 * <p/>
 * New map with generated id.
 * <pre>
 * val myMap = CassandraStorage.newMap
 * </pre>
 *
 * New map with user-defined id.
 * <pre>
 * val myMap = MongoStorage.newMap(id)
 * </pre>
 *
 * Get map by user-defined id.
 * <pre>
 * val myMap = CassandraStorage.getMap(id)
 * </pre>
 * 
 * Example Java usage:
 * <pre>
 * PersistentMap<Object, Object> myMap = MongoStorage.newMap();
 * </pre>
 * Or:
 * <pre>
 * MongoPersistentMap myMap = MongoStorage.getMap(id);
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Storage {
  type ElementType

  def newMap: PersistentMap[ElementType, ElementType]
  def newVector: PersistentVector[ElementType]
  def newRef: PersistentRef[ElementType]

  def getMap(id: String): PersistentMap[ElementType, ElementType]
  def getVector(id: String): PersistentVector[ElementType]
  def getRef(id: String): PersistentRef[ElementType]

  def newMap(id: String): PersistentMap[ElementType, ElementType]
  def newVector(id: String): PersistentVector[ElementType]
  def newRef(id: String): PersistentRef[ElementType]
}





/**
 * Implementation of <tt>PersistentMap</tt> for every concrete 
 * storage will have the same workflow. This abstracts the workflow.
 *
 * Subclasses just need to provide the actual concrete instance for the
 * abstract val <tt>storage</tt>.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentMap[K, V] extends scala.collection.mutable.Map[K, V]
  with Transactional with Committable with Logging {
  protected val newAndUpdatedEntries = TransactionalState.newMap[K, V]
  protected val removedEntries = TransactionalState.newVector[K]
  protected val shouldClearOnCommit = TransactionalRef[Boolean]()

  // to be concretized in subclasses
  val storage: MapStorageBackend[K, V]

  def commit = {
    removedEntries.toList.foreach(key => storage.removeMapStorageFor(uuid, key))
    storage.insertMapStorageEntriesFor(uuid, newAndUpdatedEntries.toList)
    if (shouldClearOnCommit.isDefined && shouldClearOnCommit.get.get)
      storage.removeMapStorageFor(uuid)
    newAndUpdatedEntries.clear
    removedEntries.clear
  }

  def -=(key: K) = remove(key)

  def +=(key: K, value: V) = put(key, value)

  override def put(key: K, value: V): Option[V] = {
    register
    newAndUpdatedEntries.put(key, value)
  }
 
  override def update(key: K, value: V) = { 
    register
    newAndUpdatedEntries.update(key, value)
  }
  
  def remove(key: K) = {
    register
    removedEntries.add(key)
  }
  
  def slice(start: Option[K], count: Int): List[Tuple2[K, V]] =
    slice(start, None, count)

  def slice(start: Option[K], finish: Option[K], count: Int): List[Tuple2[K, V]] = try {
    storage.getMapStorageRangeFor(uuid, start, finish, count)
  } catch { case e: Exception => Nil }

  override def clear = { 
    register
    shouldClearOnCommit.swap(true)
  }
  
  override def contains(key: K): Boolean = try {
    newAndUpdatedEntries.contains(key) ||
    storage.getMapStorageEntryFor(uuid, key).isDefined
  } catch { case e: Exception => false }

  override def size: Int = try {
    storage.getMapStorageSizeFor(uuid)
  } catch { case e: Exception => 0 }

  override def get(key: K): Option[V] = {
    if (newAndUpdatedEntries.contains(key)) {
      newAndUpdatedEntries.get(key)
    }
    else try {
      storage.getMapStorageEntryFor(uuid, key)
    } catch { case e: Exception => None }
  }
  
  override def elements: Iterator[Tuple2[K, V]]  = {
    new Iterator[Tuple2[K, V]] {
      private val originalList: List[Tuple2[K, V]] = try {
        storage.getMapStorageFor(uuid)
      } catch {
        case e: Throwable => Nil
      }
      private var elements = newAndUpdatedEntries.toList union originalList.reverse 
      override def next: Tuple2[K, V]= synchronized {
        val element = elements.head
        elements = elements.tail        
        element
      }
      override def hasNext: Boolean = synchronized { !elements.isEmpty }
    }
  }

  private def register = {
    if (currentTransaction.get.isEmpty) throw new NoTransactionInScopeException
    currentTransaction.get.get.register(uuid, this)
  }
}

/**
 * Implements a template for a concrete persistent transactional vector based storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentVector[T] extends RandomAccessSeq[T] with Transactional with Committable {
  protected val newElems = TransactionalState.newVector[T]
  protected val updatedElems = TransactionalState.newMap[Int, T]
  protected val removedElems = TransactionalState.newVector[T]
  protected val shouldClearOnCommit = TransactionalRef[Boolean]()

  val storage: VectorStorageBackend[T]

  def commit = {
    for (element <- newElems) storage.insertVectorStorageEntryFor(uuid, element)
    for (entry <- updatedElems) storage.updateVectorStorageEntryFor(uuid, entry._1, entry._2)
    newElems.clear
    updatedElems.clear
  }

  def +(elem: T) = add(elem)
  
  def add(elem: T) = {
    register
    newElems + elem
  }
 
  def apply(index: Int): T = get(index)

  def get(index: Int): T = {
    if (newElems.size > index) newElems(index)
    else storage.getVectorStorageEntryFor(uuid, index)
  }

  override def slice(start: Int, count: Int): RandomAccessSeq[T] = slice(Some(start), None, count)
  
  def slice(start: Option[Int], finish: Option[Int], count: Int): RandomAccessSeq[T] = {
    val buffer = new scala.collection.mutable.ArrayBuffer[T]
    storage.getVectorStorageRangeFor(uuid, start, finish, count).foreach(buffer.append(_))
    buffer
  }

  /**
   * Removes the <i>tail</i> element of this vector.
   */
  def pop: T = {
    register
    throw new UnsupportedOperationException("PersistentVector::pop is not implemented")
  }

  def update(index: Int, newElem: T) = {
    register
    storage.updateVectorStorageEntryFor(uuid, index, newElem)
  }

  override def first: T = get(0)

  override def last: T = {
    if (newElems.length != 0) newElems.last
    else {
      val len = length
      if (len == 0) throw new NoSuchElementException("Vector is empty")
      get(len - 1)
    }
  }

  def length: Int = storage.getVectorStorageSizeFor(uuid) + newElems.length

  private def register = {
    if (currentTransaction.get.isEmpty) throw new NoTransactionInScopeException
    currentTransaction.get.get.register(uuid, this)
  }
}

/**
 * Implements a persistent reference with abstract storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentRef[T] extends Transactional with Committable {
  protected val ref = new TransactionalRef[T]
  
  val storage: RefStorageBackend[T]

  def commit = if (ref.isDefined) {
    storage.insertRefStorageFor(uuid, ref.get.get)
    ref.swap(null.asInstanceOf[T]) 
  }

  def swap(elem: T) = {
    register
    ref.swap(elem)
  }
  
  def get: Option[T] = if (ref.isDefined) ref.get else storage.getRefStorageFor(uuid)

  def isDefined: Boolean = ref.isDefined || storage.getRefStorageFor(uuid).isDefined

  def getOrElse(default: => T): T = {
    val current = get
    if (current.isDefined) current.get
    else default
  }

  private def register = {
    if (currentTransaction.get.isEmpty) throw new NoTransactionInScopeException
    currentTransaction.get.get.register(uuid, this)
  }
}
