/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

import stm.TransactionManagement
import akka.collection._

import org.codehaus.aspectwerkz.proxy.Uuid

import scala.collection.mutable.{ArrayBuffer, HashMap}

sealed abstract class PersistentStateConfig
abstract class PersistentStorageConfig  extends PersistentStateConfig
case class CassandraStorageConfig extends PersistentStorageConfig
case class TerracottaStorageConfig extends PersistentStorageConfig
case class TokyoCabinetStorageConfig extends PersistentStorageConfig
case class MongoStorageConfig extends PersistentStorageConfig

/**
 * Example Scala usage:
 * <pre>
 * val myMap = PersistentState.newMap(CassandraStorageConfig)
 * </pre>
 * <p/>
 *
 * Example Java usage:
 * <pre>
 * TransactionalMap myMap = PersistentState.newMap(new CassandraStorageConfig());
 * </pre>
 */
object PersistentState {
  def newMap(config: PersistentStorageConfig): PersistentMap = config match {
    case CassandraStorageConfig() => new CassandraPersistentMap
    case MongoStorageConfig() => new MongoPersistentMap
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newVector(config: PersistentStorageConfig): PersistentVector = config match {
    case CassandraStorageConfig() => new CassandraPersistentVector
    case MongoStorageConfig() => new MongoPersistentVector
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newRef(config: PersistentStorageConfig): PersistentRef = config match {
    case CassandraStorageConfig() => new CassandraPersistentRef
    case MongoStorageConfig() => new MongoPersistentRef
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }
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
trait PersistentMap extends scala.collection.mutable.Map[AnyRef, AnyRef] with Transactional {
  protected val newAndUpdatedEntries = TransactionalState.newMap[AnyRef, AnyRef]
  protected val removedEntries = TransactionalState.newMap[AnyRef, AnyRef]
  protected val shouldClearOnCommit = TransactionalRef[Boolean]()

  // to be concretized in subclasses
  val storage: MapStorage

  def commit = {
    storage.removeMapStorageFor(uuid, removedEntries.toList)
    storage.insertMapStorageEntriesFor(uuid, newAndUpdatedEntries.toList)
    if (shouldClearOnCommit.isDefined & shouldClearOnCommit.get.get) storage.removeMapStorageFor(uuid)
    newAndUpdatedEntries.clear
    removedEntries.clear
  }

  def -=(key: AnyRef) = remove(key)

  def +=(key: AnyRef, value: AnyRef) = put(key, value)

  override def put(key: AnyRef, value: AnyRef): Option[AnyRef] = newAndUpdatedEntries.put(key, value)
 
  override def update(key: AnyRef, value: AnyRef) = newAndUpdatedEntries.update(key, value)
 
  def remove(key: AnyRef) = removedEntries.remove(key)
  
  def slice(start: Option[AnyRef], count: Int): List[Tuple2[AnyRef, AnyRef]] = slice(start, None, count)

  def slice(start: Option[AnyRef], finish: Option[AnyRef], count: Int): List[Tuple2[AnyRef, AnyRef]] = try {
      storage.getMapStorageRangeFor(uuid, start, finish, count)
    } catch { case e: Exception => Nil }

  override def clear = shouldClearOnCommit.swap(true)

  override def contains(key: AnyRef): Boolean = try {
      newAndUpdatedEntries.contains(key) || 
      storage.getMapStorageEntryFor(uuid, key).isDefined
    } catch { case e: Exception => false }

  override def size: Int = try {
      storage.getMapStorageSizeFor(uuid) + newAndUpdatedEntries.size
    } catch { case e: Exception => 0 }

  override def get(key: AnyRef): Option[AnyRef] = {
    if (newAndUpdatedEntries.contains(key)) newAndUpdatedEntries.get(key)
    else try {
        storage.getMapStorageEntryFor(uuid, key)
      } catch { case e: Exception => None }
  }
  
  override def elements: Iterator[Tuple2[AnyRef, AnyRef]]  = {
    new Iterator[Tuple2[AnyRef, AnyRef]] {
      private val originalList: List[Tuple2[AnyRef, AnyRef]] = try {
        storage.getMapStorageFor(uuid)
      } catch {
        case e: Throwable => Nil
      }
      // FIXME how to deal with updated entries, these should be replaced in the originalList not just added
      private var elements = newAndUpdatedEntries.toList ::: originalList.reverse 
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
class CassandraPersistentMap extends PersistentMap {
  val storage = CassandraStorage
}

/**
 * Implements a persistent transactional map based on the MongoDB distributed P2P key-value storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class MongoPersistentMap extends PersistentMap {
  val storage = MongoStorage
}

/**
 * Implements a template for a concrete persistent transactional vector based storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentVector extends RandomAccessSeq[AnyRef] with Transactional {
  protected val newElems = TransactionalState.newVector[AnyRef]
  protected val updatedElems = TransactionalState.newMap[Int, AnyRef]
  protected val removedElems = TransactionalState.newVector[AnyRef]
  protected val shouldClearOnCommit = TransactionalRef[Boolean]()

  val storage: VectorStorage

  def commit = {
    // FIXME: should use batch function once the bug is resolved
    for (element <- newElems) storage.insertVectorStorageEntryFor(uuid, element)
    for (entry <- updatedElems) storage.updateVectorStorageEntryFor(uuid, entry._1, entry._2)
    newElems.clear
    updatedElems.clear
  }

  def +(elem: AnyRef) = newElems + elem

  def add(elem: AnyRef) = newElems + elem
 
  def apply(index: Int): AnyRef = get(index)

  def get(index: Int): AnyRef = {
    if (newElems.size > index) newElems(index)
    else storage.getVectorStorageEntryFor(uuid, index)
  }

  override def slice(start: Int, count: Int): RandomAccessSeq[AnyRef] = slice(Some(start), None, count)
  
  def slice(start: Option[Int], finish: Option[Int], count: Int): RandomAccessSeq[AnyRef] =
    storage.getVectorStorageRangeFor(uuid, start, finish, count)

  /**
   * Removes the <i>tail</i> element of this vector.
   */
  // FIXME: implement persistent vector pop 
  def pop: AnyRef = throw new UnsupportedOperationException("need to implement persistent vector pop")

  def update(index: Int, newElem: AnyRef) = storage.updateVectorStorageEntryFor(uuid, index, newElem)

  override def first: AnyRef = get(0)

  override def last: AnyRef = {
    if (newElems.length != 0) newElems.last
    else {
      val len = length
      if (len == 0) throw new NoSuchElementException("Vector is empty")
      get(len - 1)
    }
  }

  def length: Int = storage.getVectorStorageSizeFor(uuid) + newElems.length
}

/**
 * Implements a persistent transactional vector based on the Cassandra distributed P2P key-value storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentVector extends PersistentVector {
  val storage = CassandraStorage
}

/**
 * Implements a persistent transactional vector based on the MongoDB distributed P2P key-value storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debaissh Ghosh</a>
 */
class MongoPersistentVector extends PersistentVector {
  val storage = MongoStorage
} 

/**
 * Implements a persistent reference with abstract storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentRef extends Transactional {
  protected val ref = new TransactionalRef[AnyRef]
  
  val storage: RefStorage

  def commit = if (ref.isDefined) {
    storage.insertRefStorageFor(uuid, ref.get)
    ref.swap(null) 
  }

  def swap(elem: AnyRef) = ref.swap(elem)
  
  def get: Option[AnyRef] = if (ref.isDefined) ref.get else storage.getRefStorageFor(uuid)

  def isDefined: Boolean = ref.isDefined || storage.getRefStorageFor(uuid).isDefined

  def getOrElse(default: => AnyRef): AnyRef = {
    val current = get
    if (current.isDefined) current
    else default
  }
}

class CassandraPersistentRef extends PersistentRef {
  val storage = CassandraStorage
}

class MongoPersistentRef extends PersistentRef {
  val storage = MongoStorage
}
