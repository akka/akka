/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.state

import kernel.stm.TransactionManagement
import akka.collection._

import org.codehaus.aspectwerkz.proxy.Uuid

import scala.collection.mutable.{ArrayBuffer, HashMap}

sealed abstract class TransactionalStateConfig
abstract class PersistentStorageConfig  extends TransactionalStateConfig
case class CassandraStorageConfig extends PersistentStorageConfig
case class TerracottaStorageConfig extends PersistentStorageConfig
case class TokyoCabinetStorageConfig extends PersistentStorageConfig

/**
 * Scala API.
 * <p/>
 * Example Scala usage:
 * <pre>
 * val myMap = TransactionalState.newPersistentMap(CassandraStorageConfig)
 * </pre>
 */
object TransactionalState extends TransactionalState

/**
 * Java API.
 * <p/>
 * Example Java usage:
 * <pre>
 * TransactionalState state = new TransactionalState();
 * TransactionalMap myMap = state.newPersistentMap(new CassandraStorageConfig());
 * </pre>
 */
class TransactionalState {
  def newPersistentMap(config: PersistentStorageConfig): TransactionalMap[AnyRef, AnyRef] = config match {
    case CassandraStorageConfig() => new CassandraPersistentTransactionalMap
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newPersistentVector(config: PersistentStorageConfig): TransactionalVector[AnyRef] = config match {
    case CassandraStorageConfig() => new CassandraPersistentTransactionalVector
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newPersistentRef(config: PersistentStorageConfig): TransactionalRef[AnyRef] = config match {
    case CassandraStorageConfig() => new CassandraPersistentTransactionalRef
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newInMemoryMap[K, V]: TransactionalMap[K, V] = new InMemoryTransactionalMap[K, V]

  def newInMemoryVector[T]: TransactionalVector[T] = new InMemoryTransactionalVector[T]

  def newInMemoryRef[T]: TransactionalRef[T] = new TransactionalRef[T]
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable
trait Transactional {
  // FIXME: won't work across the cluster
  val uuid = Uuid.newUuid.toString

  private[kernel] def begin
  private[kernel] def commit
  private[kernel] def rollback

  protected def verifyTransaction = {
    val cflowTx = TransactionManagement.threadBoundTx.get
    if (!cflowTx.isDefined) {
      throw new IllegalStateException("Can't access transactional reference outside the scope of a transaction [" + this + "]")
    } else {
      cflowTx.get.register(this)
    }
  }
}

/**
 * Base trait for all state implementations (persistent or in-memory).
 *
 * FIXME: Create Java versions using pcollections
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait TransactionalMap[K, V] extends Transactional with scala.collection.mutable.Map[K, V] {
  override def hashCode: Int = System.identityHashCode(this);
  override def equals(other: Any): Boolean = false
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
  override def contains(key: K): Boolean = {
    verifyTransaction
    state.contains(key)
  }

  override def clear = {
    verifyTransaction
    state = new HashTrie[K, V]
  }

  override def size: Int = {
    verifyTransaction
    state.size
  }

  // ---- For scala.collection.mutable.Map ----
  override def remove(key: K) = {
    verifyTransaction
    state = state - key
  }

  override def elements: Iterator[(K, V)] = {
//    verifyTransaction
    state.elements
  }

  override def get(key: K): Option[V] = {
    verifyTransaction
    state.get(key)
  }

  override def put(key: K, value: V): Option[V] = {
    verifyTransaction
    val oldValue = state.get(key)
    state = state.update(key, value)
    oldValue
  }

  override def -=(key: K) = {
    verifyTransaction
    remove(key)
  }

  override def update(key: K, value: V) = {
    verifyTransaction
    put(key, value)
  }
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

  def getRange(start: Option[AnyRef], count: Int)

  // ---- For Transactional ----
  override def begin = {}

  override def rollback = changeSet.clear
 
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
 * Implements a persistent transactional map based on the Cassandra distributed P2P key-value storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentTransactionalMap extends PersistentTransactionalMap[AnyRef, AnyRef] {

  override def remove(key: AnyRef) = {
    verifyTransaction
    if (changeSet.contains(key)) changeSet -= key
    else CassandraStorage.removeMapStorageFor(uuid, key)
  }

  override def getRange(start: Option[AnyRef], count: Int) =
    getRange(start, None, count)

  def getRange(start: Option[AnyRef], finish: Option[AnyRef], count: Int) = {
    verifyTransaction
    try {
      CassandraStorage.getMapStorageRangeFor(uuid, start, finish, count)
    } catch {
      case e: Exception => Nil
    }
  }

  // ---- For Transactional ----
  override def commit = {
    CassandraStorage.insertMapStorageEntriesFor(uuid, changeSet.toList)
    changeSet.clear
  }

  // ---- Overriding scala.collection.mutable.Map behavior ----
  override def clear = {
    verifyTransaction
    try {
      CassandraStorage.removeMapStorageFor(uuid)
    } catch {
      case e: Exception => {}
    }
  }

  override def contains(key: AnyRef): Boolean = {
    try {
      verifyTransaction
      CassandraStorage.getMapStorageEntryFor(uuid, key).isDefined
    } catch {
      case e: Exception => false
    }
  }

  override def size: Int = {
    verifyTransaction
    try {
      CassandraStorage.getMapStorageSizeFor(uuid)
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
        CassandraStorage.getMapStorageEntryFor(uuid, key)
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
        CassandraStorage.getMapStorageFor(uuid)
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
 * Base for all transactional vector implementations.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TransactionalVector[T] extends Transactional with RandomAccessSeq[T] {
  override def hashCode: Int = System.identityHashCode(this);
  override def equals(other: Any): Boolean = false

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

  def add(elem: T) = {
    verifyTransaction
    state = state + elem
  }

  def get(index: Int): T = {
    verifyTransaction
    state(index)
  }

  def getRange(start: Int, count: Int): List[T] = {
    verifyTransaction
    state.slice(start, count).toList.asInstanceOf[List[T]]
  }

  // ---- For Transactional ----
  override def begin = snapshot = state

  override def commit = snapshot = state

  override def rollback = state = snapshot

  // ---- For Seq ----
  def length: Int = {
    verifyTransaction
    state.length
  }

  def apply(index: Int): T = {
    verifyTransaction
    state(index)
  }

  override def elements: Iterator[T] = {
    //verifyTransaction
    state.elements
  }

  override def toList: List[T] = {
    verifyTransaction
    state.toList
  }
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

  // ---- For Transactional ----
  override def begin = {}

  override def rollback = changeSet.clear

  // ---- For TransactionalVector ----
  override def add(value: T) = {
    verifyTransaction
    changeSet += value
  }
}

/**
 * Implements a persistent transactional vector based on the Cassandra distributed P2P key-value storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class CassandraPersistentTransactionalVector extends PersistentTransactionalVector[AnyRef] {

  // ---- For TransactionalVector ----
  override def get(index: Int): AnyRef = {
    verifyTransaction
    if (changeSet.size > index) changeSet(index)
    else CassandraStorage.getVectorStorageEntryFor(uuid, index)
  }

  override def getRange(start: Int, count: Int): List[AnyRef] =
    getRange(Some(start), None, count)
  
  def getRange(start: Option[Int], finish: Option[Int], count: Int): List[AnyRef] = {
    verifyTransaction
    CassandraStorage.getVectorStorageRangeFor(uuid, start, finish, count)
  }

  override def length: Int = {
    verifyTransaction
    CassandraStorage.getVectorStorageSizeFor(uuid)
  }

  override def apply(index: Int): AnyRef = get(index)

  override def first: AnyRef = get(0)

  override def last: AnyRef = {
    verifyTransaction
    val l = length
    if (l == 0) throw new NoSuchElementException("Vector is empty")
    get(length - 1)
  }

  // ---- For Transactional ----
  override def commit = {
    // FIXME: should use batch function once the bug is resolved
    for (element <- changeSet) CassandraStorage.insertVectorStorageEntryFor(uuid, element)
    changeSet.clear
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

  def swap(elem: T) = {
    verifyTransaction
    ref = Some(elem)
  }

  def get: Option[T] = {
    verifyTransaction
    ref
  }

  def getOrElse(default: => T): T = {
    verifyTransaction
    ref.getOrElse(default)
  }

  def isDefined: Boolean = {
    verifyTransaction
    ref.isDefined
  }
}

class CassandraPersistentTransactionalRef extends TransactionalRef[AnyRef] {
  override def commit = if (ref.isDefined) {
    CassandraStorage.insertRefStorageFor(uuid, ref.get)
    ref = None 
  }

  override def rollback = ref = None

  override def get: Option[AnyRef] = {
    verifyTransaction
    CassandraStorage.getRefStorageFor(uuid)
  }

  override def isDefined: Boolean = get.isDefined

  override def getOrElse(default: => AnyRef): AnyRef = {
    val ref = get
    if (ref.isDefined) ref
    else default
  }
}
