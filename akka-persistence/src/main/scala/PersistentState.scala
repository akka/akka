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
case class CassandraStorageConfig() extends PersistentStorageConfig
case class TerracottaStorageConfig() extends PersistentStorageConfig
case class TokyoCabinetStorageConfig() extends PersistentStorageConfig
case class MongoStorageConfig() extends PersistentStorageConfig

/**
 * Scala API.
 * <p/>
 * Example Scala usage:
 * <pre>
 * val myMap = PersistentState.newMap(CassandraStorageConfig)
 * </pre>
 * Java API.
 * <p/>
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
 * Base class for all persistent transactional map implementations should extend.
 * Implements a Unit of Work, records changes into a change set.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class PersistentMap extends TransactionalMap[AnyRef, AnyRef] {

  // FIXME: need to handle remove in another changeSet
  protected[akka] val changeSet = new HashMap[AnyRef, AnyRef]

  def getRange(start: Option[AnyRef], count: Int)

  // ---- For Transactional ----
  override def begin = {}

  override def rollback = changeSet.clear
 
  // ---- For scala.collection.mutable.Map ----
  override def put(key: AnyRef, value: AnyRef): Option[AnyRef] = {
    verifyTransaction
    changeSet += key -> value
    None // always return None to speed up writes (else need to go to DB to get
  }

  override def -=(key: AnyRef) = remove(key)

  override def update(key: AnyRef, value: AnyRef) = put(key, value)
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
abstract class TemplatePersistentMap extends PersistentMap {

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
      storage.getMapStorageEntryFor(uuid, key).isDefined
    } catch {
      case e: Exception => false
    }
  }

  override def size: Int = {
    try {
      storage.getMapStorageSizeFor(uuid)
    } catch {
      case e: Exception => 0
    }
  }

  // ---- For scala.collection.mutable.Map ----
  override def get(key: AnyRef): Option[AnyRef] = {
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
class CassandraPersistentMap extends TemplatePersistentMap {
  val storage = CassandraStorage
}

/**
 * Implements a persistent transactional map based on the MongoDB distributed P2P key-value storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
class MongoPersistentMap extends TemplatePersistentMap {
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
abstract class PersistentVector extends TransactionalVector[AnyRef] {

  // FIXME: need to handle remove in another changeSet
  protected[akka] val changeSet = new ArrayBuffer[AnyRef]

  // ---- For Transactional ----
  override def begin = {}

  override def rollback = changeSet.clear

  // ---- For TransactionalVector ----
  override def add(value: AnyRef) = {
    verifyTransaction
    changeSet += value
  }
}

/**
 * Implements a template for a concrete persistent transactional vector based storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
abstract class TemplatePersistentVector extends PersistentVector {

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

  // ---- For Transactional ----
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
class CassandraPersistentVector extends TemplatePersistentVector {
  val storage = CassandraStorage
}

/**                                                                                                                                           
 * Implements a persistent transactional vector based on the MongoDB distributed P2P key-value storage.
 *
 * @author <a href="http://debasishg.blogspot.com">Debaissh Ghosh</a>
 */
class MongoPersistentVector extends TemplatePersistentVector {
  val storage = MongoStorage
} 

abstract class PersistentRef extends TransactionalRef[AnyRef] {
  val storage: RefStorage

  override def commit = if (ref.isDefined) {
    storage.insertRefStorageFor(uuid, ref.get)
    ref = None 
  }

  override def rollback = ref = None

  override def get: Option[AnyRef] = {
    verifyTransaction
    storage.getRefStorageFor(uuid)
  }

  override def isDefined: Boolean = get.isDefined

  override def getOrElse(default: => AnyRef): AnyRef = {
    val ref = get
    if (ref.isDefined) ref
    else default
  }
}

class CassandraPersistentRef extends PersistentRef {
  val storage = CassandraStorage
}

class MongoPersistentRef extends PersistentRef {
  val storage = MongoStorage
}
