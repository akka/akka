/**
 * Copyright (C) 2009 Scalable Solutions.
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
  // FIXME: The UUID won't work across the remote machines, use [http://johannburkard.de/software/uuid/]
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

object CassandraStorage extends Storage {
  type ElementType = Array[Byte]

  def newMap: PersistentMap[ElementType, ElementType] = newMap(Uuid.newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(Uuid.newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(Uuid.newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new CassandraPersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new CassandraPersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new CassandraPersistentRef(id)
}

object MongoStorage extends Storage {
  type ElementType = AnyRef

  def newMap: PersistentMap[ElementType, ElementType] = newMap(Uuid.newUuid.toString)
  def newVector: PersistentVector[ElementType] = newVector(Uuid.newUuid.toString)
  def newRef: PersistentRef[ElementType] = newRef(Uuid.newUuid.toString)

  def getMap(id: String): PersistentMap[ElementType, ElementType] = newMap(id)
  def getVector(id: String): PersistentVector[ElementType] = newVector(id)
  def getRef(id: String): PersistentRef[ElementType] = newRef(id)

  def newMap(id: String): PersistentMap[ElementType, ElementType] = new MongoPersistentMap(id)
  def newVector(id: String): PersistentVector[ElementType] = new MongoPersistentVector(id)
  def newRef(id: String): PersistentRef[ElementType] = new MongoPersistentRef(id)
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
      // FIXME how to deal with updated entries, these should be replaced in the originalList not just added
      private var elements = newAndUpdatedEntries.toList ::: originalList.reverse 
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
 * Implements a persistent transactional map based on the Cassandra distributed P2P key-value storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentMap(id: String) extends PersistentMap[Array[Byte], Array[Byte]] {
  val uuid = id
  val storage = CassandraStorageBackend
}

/**
 * Implements a persistent transactional map based on the MongoDB document storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class MongoPersistentMap(id: String) extends PersistentMap[AnyRef, AnyRef] {
  val uuid = id
  val storage = MongoStorageBackend
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
    // FIXME: should use batch function once the bug is resolved
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
  // FIXME: implement persistent vector pop 
  def pop: T = {
    register
    throw new UnsupportedOperationException("need to implement persistent vector pop")
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
 * Implements a persistent transactional vector based on the Cassandra
 * distributed P2P key-value storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentVector(id: String) extends PersistentVector[Array[Byte]] {
  val uuid = id
  val storage = CassandraStorageBackend
}

/**                                                                                                                                           
 * Implements a persistent transactional vector based on the MongoDB
 * document  storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debaissh Ghosh</a>
 */
class MongoPersistentVector(id: String) extends PersistentVector[AnyRef] {
  val uuid = id
  val storage = MongoStorageBackend
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

class CassandraPersistentRef(id: String) extends PersistentRef[Array[Byte]] {
  val uuid = id
  val storage = CassandraStorageBackend
}

class MongoPersistentRef(id: String) extends PersistentRef[AnyRef] {
  val uuid = id
  val storage = MongoStorageBackend
}
