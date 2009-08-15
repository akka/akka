/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.state

import kernel.stm.{Ref, TransactionManagement}
import akka.collection._

import org.codehaus.aspectwerkz.proxy.Uuid

import scala.collection.mutable.{ArrayBuffer, HashMap}

sealed abstract class TransactionalStateConfig
abstract class PersistentStorageConfig  extends TransactionalStateConfig
case class CassandraStorageConfig extends PersistentStorageConfig
case class TerracottaStorageConfig extends PersistentStorageConfig
case class TokyoCabinetStorageConfig extends PersistentStorageConfig
case class MongoStorageConfig extends PersistentStorageConfig

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
    case MongoStorageConfig() => new MongoPersistentTransactionalMap
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newPersistentVector(config: PersistentStorageConfig): TransactionalVector[AnyRef] = config match {
    case CassandraStorageConfig() => new CassandraPersistentTransactionalVector
    case MongoStorageConfig() => new MongoPersistentTransactionalVector
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newPersistentRef(config: PersistentStorageConfig): TransactionalRef[AnyRef] = config match {
    case CassandraStorageConfig() => new CassandraPersistentTransactionalRef
    case MongoStorageConfig() => throw new UnsupportedOperationException
    case TerracottaStorageConfig() => throw new UnsupportedOperationException
    case TokyoCabinetStorageConfig() => throw new UnsupportedOperationException
  }

  def newMap[K, V]: TransactionalMap[K, V] = new InMemoryTransactionalMap[K, V]

  def newVector[T]: TransactionalVector[T] = new InMemoryTransactionalVector[T]

  def newRef[T]: TransactionalRef[T] = new TransactionalRef[T]
  def newRef[T](init: T): TransactionalRef[T] = new TransactionalRef[T](init)
}

/**
 * Implements a transactional reference. Based on the Multiverse STM.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalRef[T](elem: T) extends Transactional {
  def this() = this(null.asInstanceOf[T])

  private[kernel] val ref = new Ref[T](elem)

  def swap(elem: T) = ref.set(elem)

  def get: Option[T] = {
    if (ref.isNull) None
    else Some(ref.get)
  }

  def getOrWait: T = ref.getOrAwait

  def getOrElse(default: => T): T = {
    if (ref.isNull) default
    else ref.get
  }

  def isDefined: Boolean = !ref.isNull
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable
trait Transactional {
  // FIXME: won't work across the cluster
  val uuid = Uuid.newUuid.toString
 
  private[kernel] def commit = {}

  protected def verifyTransaction = {
    val cflowTx = TransactionManagement.threadBoundTx.get
    if (!cflowTx.isDefined) throw new IllegalStateException("Can't access transactional reference outside the scope of a transaction [" + this + "]")
    else cflowTx.get.register(this)
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
  override def hashCode: Int = System.identityHashCode(this)
  override def equals(other: Any): Boolean = false
  def remove(key: K)
}

/**
 * Base for all transactional vector implementations.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class TransactionalVector[T] extends Transactional with RandomAccessSeq[T] {
  override def hashCode: Int = System.identityHashCode(this)
  override def equals(other: Any): Boolean = false

  def add(elem: T)

  def get(index: Int): T

  def getRange(start: Int, count: Int): List[T]
}


