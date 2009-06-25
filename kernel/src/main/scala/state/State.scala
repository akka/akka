/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.state

import org.codehaus.aspectwerkz.proxy.Uuid

import kernel.actor.ActiveObject
import se.scalablesolutions.akka.collection._

import scala.collection.mutable.HashMap

sealed abstract class TransactionalStateConfig
abstract class TransactionalMapConfig  extends TransactionalStateConfig
abstract class TransactionalVectorConfig  extends TransactionalStateConfig
abstract class TransactionalRefConfig  extends TransactionalStateConfig

abstract class PersistentStorageConfig  extends TransactionalStateConfig
case class CassandraStorageConfig extends PersistentStorageConfig
case class TerracottaStorageConfig extends PersistentStorageConfig
case class TokyoCabinetStorageConfig extends PersistentStorageConfig

case class PersistentMapConfig(storage: PersistentStorageConfig) extends TransactionalMapConfig
case class InMemoryMapConfig extends TransactionalMapConfig

case class PersistentVectorConfig(storage: PersistentStorageConfig) extends TransactionalVectorConfig
case class InMemoryVectorConfig extends TransactionalVectorConfig

case class PersistentRefConfig(storage: PersistentStorageConfig) extends TransactionalRefConfig
case class InMemoryRefConfig extends TransactionalRefConfig

object TransactionalState extends TransactionalState
class TransactionalState {

  /**
   * Usage:
   * <pre>
   * val myMap = TransactionalState.newMap(PersistentMapConfig(CassandraStorageConfig))
   * </pre>
   */
  def newMap(config: TransactionalMapConfig) = config match {
    case PersistentMapConfig(storage) => storage match {
      case CassandraStorageConfig() => new CassandraPersistentTransactionalMap
      case TerracottaStorageConfig() => throw new UnsupportedOperationException
      case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
    }
    case InMemoryMapConfig() => new InMemoryTransactionalMap
  }

  /**
   * Usage:
   * <pre>
   * val myVector = TransactionalState.newVector(PersistentVectorConfig(CassandraStorageConfig))
   * </pre>
   */
  def newVector(config: TransactionalVectorConfig) = config match {
    case PersistentVectorConfig(storage) => storage match {
      case CassandraStorageConfig() => new CassandraPersistentTransactionalVector
      case TerracottaStorageConfig() => throw new UnsupportedOperationException
      case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
    }
    case InMemoryVectorConfig() => new InMemoryTransactionalVector
  }

  /**
   * Usage:
   * <pre>
   * val myRef = TransactionalState.newRef(PersistentRefConfig(CassandraStorageConfig))
   * </pre>
   */
  def newRef(config: TransactionalRefConfig) = config match {
    case PersistentRefConfig(storage) => storage match {
      case CassandraStorageConfig() => new CassandraPersistentTransactionalRef
      case TerracottaStorageConfig() => throw new UnsupportedOperationException
      case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
    }
    case InMemoryRefConfig() => new TransactionalRef
  }
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable
trait Transactional {
  val uuid = Uuid.newUuid.toString

  private[kernel] def begin
  private[kernel] def commit
  private[kernel] def rollback

  protected def isInTransaction = ActiveObject.threadBoundTx.get.isDefined
  protected def nonTransactionalCall = throw new IllegalStateException("Can't access transactional map outside the scope of a transaction")
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

trait TransactionalMapGuard[K, V] extends TransactionalMap[K, V] with Transactional {
  abstract override def contains(key: K): Boolean =
    if (isInTransaction) super.contains(key)
    else nonTransactionalCall
  abstract override def clear =
    if (isInTransaction) super.clear
    else nonTransactionalCall
  abstract override def size: Int =
    if (isInTransaction) super.size
    else nonTransactionalCall
  abstract override def remove(key: K) =
    if (isInTransaction) super.remove(key)
    else nonTransactionalCall
  abstract override def elements: Iterator[(K, V)] = 
    if (isInTransaction) super.elements
    else nonTransactionalCall
  abstract override def get(key: K): Option[V] =
    if (isInTransaction) super.get(key)
    else nonTransactionalCall
  abstract override def put(key: K, value: V): Option[V] =
    if (isInTransaction) super.put(key, value)
    else nonTransactionalCall
  abstract override def -=(key: K) =
    if (isInTransaction) super.-=(key)
    else nonTransactionalCall
  abstract override def update(key: K, value: V) =
    if (isInTransaction) super.update(key, value)
    else nonTransactionalCall
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
 * Implements a persistent transactional map based on the Cassandra distributed P2P key-value storage. 
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentTransactionalMap extends PersistentTransactionalMap[String, AnyRef] {

  override def getRange(start: Int, count: Int) = CassandraNode.getMapStorageRangeFor(uuid, start, count)

  // ---- For Transactional ----
  override def commit = {
    // FIXME: should use batch function once the bug is resolved
    for (entry <- changeSet) {
      val (key, value) = entry
      CassandraNode.insertMapStorageEntryFor(uuid, key, value)
    }
  }

  // ---- Overriding scala.collection.mutable.Map behavior ----
  override def clear = CassandraNode.removeMapStorageFor(uuid)
  override def contains(key: String): Boolean = CassandraNode.getMapStorageEntryFor(uuid, key).isDefined
  override def size: Int = CassandraNode.getMapStorageSizeFor(uuid)

  // ---- For scala.collection.mutable.Map ----
  override def get(key: String): Option[AnyRef] =  CassandraNode.getMapStorageEntryFor(uuid, key)
  override def elements: Iterator[Tuple2[String, AnyRef]]  = {
    new Iterator[Tuple2[String, AnyRef]] {
      private val originalList: List[Tuple2[String, AnyRef]] = CassandraNode.getMapStorageFor(uuid)
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
 * Base for all transactional vector implementations.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TransactionalVector[T] extends Transactional with RandomAccessSeq[T] {
  def add(elem: T)
  def get(index: Int): T
  def getRange(start: Int, count: Int): List[T]
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

  def add(elem: T) = state = state + elem
  def get(index: Int): T = state(index)
  def getRange(start: Int, count: Int): List[T] = state.slice(start, count).toList.asInstanceOf[List[T]]

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
 * Base class for all persistent transactional vector implementations should extend.
 * Implements a Unit of Work, records changes into a change set.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class PersistentTransactionalVector[T] extends TransactionalVector[T] {

  // FIXME: need to handle remove in another changeSet
  protected[kernel] var changeSet: List[T] = Nil

  // ---- For Transactional ----
  override def begin = changeSet = Nil
  override def rollback = {}

  // ---- For TransactionalVector ----
  override def add(value: T) = changeSet ::= value
}

/**
 * Implements a persistent transactional vector based on the Cassandra distributed P2P key-value storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentTransactionalVector extends PersistentTransactionalVector[AnyRef] {

  // ---- For TransactionalVector ----
  override def get(index: Int): AnyRef = CassandraNode.getVectorStorageEntryFor(uuid, index)
  override def getRange(start: Int, count: Int): List[AnyRef] = CassandraNode.getVectorStorageRangeFor(uuid, start, count)
  override def length: Int = CassandraNode.getVectorStorageSizeFor(uuid)
  override def apply(index: Int): AnyRef = get(index)
  override def first: AnyRef = get(0)
  override def last: AnyRef = get(length)

  // ---- For Transactional ----
  override def commit = {
    // FIXME: should use batch function once the bug is resolved
    for (element <- changeSet) CassandraNode.insertVectorStorageEntryFor(uuid, element)
  }
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
  def isDefined: Boolean = ref.isDefined
}

class CassandraPersistentTransactionalRef extends TransactionalRef[AnyRef] {
  override def commit = if (ref.isDefined) CassandraNode.insertRefStorageFor(uuid, ref.get)
  override def get: Option[AnyRef] = CassandraNode.getRefStorageFor(uuid)
  override def isDefined: Boolean = get.isDefined
  override def getOrElse(default: => AnyRef): AnyRef = {
    val ref = get
    if (ref.isDefined) ref
    else default
  }
}