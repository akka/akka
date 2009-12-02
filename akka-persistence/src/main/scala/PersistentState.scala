/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.stm.TransactionManagement.currentTransaction
import se.scalablesolutions.akka.collection._
import se.scalablesolutions.akka.util.Logging

import org.codehaus.aspectwerkz.proxy.Uuid

class NoTransactionInScopeException extends RuntimeException

sealed abstract class PersistentStateConfig
abstract class PersistentStorageConfig  extends PersistentStateConfig
case class CassandraStorageConfig() extends PersistentStorageConfig
case class TerracottaStorageConfig() extends PersistentStorageConfig
case class TokyoCabinetStorageConfig() extends PersistentStorageConfig
case class MongoStorageConfig() extends PersistentStorageConfig

/**
 * Example Scala usage.
 * <p/>
 * New map with generated id.
 * <pre>
 * val myMap = PersistentState.newMap(CassandraStorageConfig)
 * </pre>
 *
 * New map with user-defined id.
 * <pre>
 * val myMap = PersistentState.newMap(CassandraStorageConfig, id)
 * </pre>
 *
 * Get map by user-defined id.
 * <pre>
 * val myMap = PersistentState.getMap(CassandraStorageConfig, id)
 * </pre>
 * 
 * Example Java usage:
 * <pre>
 * TransactionalMap myMap = PersistentState.newMap(new CassandraStorageConfig());
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object PersistentState {
  def newMap(config: PersistentStorageConfig): PersistentMap =
    // FIXME: won't work across the remote machines, use [http://johannburkard.de/software/uuid/]
    newMap(config, Uuid.newUuid.toString)

  def newVector(config: PersistentStorageConfig): PersistentVector =
    newVector(config, Uuid.newUuid.toString)

  def newRef(config: PersistentStorageConfig): PersistentRef =
    newRef(config, Uuid.newUuid.toString)

  def getMap(config: PersistentStorageConfig, id: String): PersistentMap =
    newMap(config, id)

  def getVector(config: PersistentStorageConfig, id: String): PersistentVector =
    newVector(config, id)

  def getRef(config: PersistentStorageConfig, id: String): PersistentRef =
    newRef(config, id)

  def newMap(config: PersistentStorageConfig, id: String): PersistentMap = config match {
    case CassandraStorageConfig() => new CassandraPersistentMap(id)
    case MongoStorageConfig() => new MongoPersistentMap(id)
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newVector(config: PersistentStorageConfig, id: String): PersistentVector = config match {
    case CassandraStorageConfig() => new CassandraPersistentVector(id)
    case MongoStorageConfig() => new MongoPersistentVector(id)
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newRef(config: PersistentStorageConfig, id: String): PersistentRef = config match {
    case CassandraStorageConfig() => new CassandraPersistentRef(id)
    case MongoStorageConfig() => new MongoPersistentRef(id)
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
trait PersistentMap extends scala.collection.mutable.Map[AnyRef, AnyRef]
  with Transactional with Committable with Logging {
  protected val newAndUpdatedEntries = TransactionalState.newMap[AnyRef, AnyRef]
  protected val removedEntries = TransactionalState.newVector[AnyRef]
  protected val shouldClearOnCommit = TransactionalRef[Boolean]()

  // to be concretized in subclasses
  val storage: MapStorage

  def commit = {
    storage.removeMapStorageFor(uuid, removedEntries.toList)
    storage.insertMapStorageEntriesFor(uuid, newAndUpdatedEntries.toList)
    if (shouldClearOnCommit.isDefined && shouldClearOnCommit.get.get)
      storage.removeMapStorageFor(uuid)
    newAndUpdatedEntries.clear
    removedEntries.clear
  }

  def -=(key: AnyRef) = remove(key)

  def +=(key: AnyRef, value: AnyRef) = put(key, value)

  override def put(key: AnyRef, value: AnyRef): Option[AnyRef] = { 
    register
    newAndUpdatedEntries.put(key, value)
  }
 
  override def update(key: AnyRef, value: AnyRef) = { 
    register
    newAndUpdatedEntries.update(key, value)
  }
  
  def remove(key: AnyRef) = { 
    register
    removedEntries.add(key)
  }
  
  def slice(start: Option[AnyRef], count: Int): List[Tuple2[AnyRef, AnyRef]] =
    slice(start, None, count)

  def slice(start: Option[AnyRef], finish: Option[AnyRef], count: Int):
    List[Tuple2[AnyRef, AnyRef]] = try {
    storage.getMapStorageRangeFor(uuid, start, finish, count)
  } catch { case e: Exception => Nil }

  override def clear = { 
    register
    shouldClearOnCommit.swap(true)
  }
  
  override def contains(key: AnyRef): Boolean = try {
    newAndUpdatedEntries.contains(key) ||
    storage.getMapStorageEntryFor(uuid, key).isDefined
  } catch { case e: Exception => false }

  override def size: Int = try {
    storage.getMapStorageSizeFor(uuid)
  } catch { case e: Exception => 0 }

  override def get(key: AnyRef): Option[AnyRef] = {
    if (newAndUpdatedEntries.contains(key)) {
      newAndUpdatedEntries.get(key)
    }
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
class CassandraPersistentMap(id: String) extends PersistentMap {
  val uuid = id
  val storage = CassandraStorage
}

/**
 * Implements a persistent transactional map based on the MongoDB document storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class MongoPersistentMap(id: String) extends PersistentMap {
  val uuid = id
  val storage = MongoStorage
}

/**
 * Implements a template for a concrete persistent transactional vector based storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentVector extends RandomAccessSeq[AnyRef] with Transactional with Committable {
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

  def +(elem: AnyRef) = add(elem)
  
  def add(elem: AnyRef) = { 
    register
    newElems + elem
  }
 
  def apply(index: Int): AnyRef = get(index)

  def get(index: Int): AnyRef = {
    if (newElems.size > index) newElems(index)
    else storage.getVectorStorageEntryFor(uuid, index)
  }

  override def slice(start: Int, count: Int): RandomAccessSeq[AnyRef] = slice(Some(start), None, count)
  
  def slice(start: Option[Int], finish: Option[Int], count: Int): RandomAccessSeq[AnyRef] = {
    val buffer = new scala.collection.mutable.ArrayBuffer[AnyRef]
    storage.getVectorStorageRangeFor(uuid, start, finish, count).foreach(buffer.append(_))
    buffer
  }

  /**
   * Removes the <i>tail</i> element of this vector.
   */
  // FIXME: implement persistent vector pop 
  def pop: AnyRef = { 
    register
    throw new UnsupportedOperationException("need to implement persistent vector pop")
  }

  def update(index: Int, newElem: AnyRef) = {
    register
    storage.updateVectorStorageEntryFor(uuid, index, newElem)
  }

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
class CassandraPersistentVector(id: String) extends PersistentVector {
  val uuid = id
  val storage = CassandraStorage
}

/**                                                                                                                                           
 * Implements a persistent transactional vector based on the MongoDB
 * document  storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debaissh Ghosh</a>
 */
class MongoPersistentVector(id: String) extends PersistentVector {
  val uuid = id
  val storage = MongoStorage
} 

/**
 * Implements a persistent reference with abstract storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentRef extends Transactional with Committable {
  protected val ref = new TransactionalRef[AnyRef]
  
  val storage: RefStorage

  def commit = if (ref.isDefined) {
    storage.insertRefStorageFor(uuid, ref.get.get)
    ref.swap(null) 
  }

  def swap(elem: AnyRef) = { 
    register
    ref.swap(elem)
  }
  
  def get: Option[AnyRef] = if (ref.isDefined) ref.get else storage.getRefStorageFor(uuid)

  def isDefined: Boolean = ref.isDefined || storage.getRefStorageFor(uuid).isDefined

  def getOrElse(default: => AnyRef): AnyRef = {
    val current = get
    if (current.isDefined) current.get
    else default
  }

  private def register = {
    if (currentTransaction.get.isEmpty) throw new NoTransactionInScopeException
    currentTransaction.get.get.register(uuid, this)
  }
}

class CassandraPersistentRef(id: String) extends PersistentRef {
  val uuid = id
  val storage = CassandraStorage
}

class MongoPersistentRef(id: String) extends PersistentRef {
  val uuid = id
  val storage = MongoStorage
}
