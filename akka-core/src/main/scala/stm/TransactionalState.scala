/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.util.UUID

import org.multiverse.stms.alpha.AlphaRef

/**
 * Example Scala usage:
 * <pre>
 * val myMap = TransactionalState.newMap
 * val myVector = TransactionalState.newVector
 * val myRef = TransactionalState.newRef
 * </pre>
 * Or:
 * <pre>
 * val myMap = TransactionalMap()
 * val myVector = TransactionalVector()
 * val myRef = TransactionalRef()
 * </pre>
 * 
 * <p/>
 * Example Java usage:
 * <pre>
 * TransactionalMap myMap = TransactionalState.newMap();
 * </pre>
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionalState {
  def newMap[K, V] = TransactionalMap[K, V]()
  def newVector[T] = TransactionalVector[T]()
  def newRef[T] = TransactionalRef[T]()
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable
trait Transactional {
  val uuid: String
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Committable {
  def commit: Unit
}

/**
 * Alias to TransactionalRef.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Ref {
  def apply[T]() = new Ref[T]
}

/**
 * Alias to Ref.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionalRef {

  /**
   * An implicit conversion that converts an Option to an Iterable value.
   */
  implicit def ref2Iterable[T](ref: TransactionalRef[T]): Iterable[T] = ref.toList

  def apply[T]() = new TransactionalRef[T]
}

/**
 * Implements a transactional managed reference. 
 * Alias to TransactionalRef.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Ref[T] extends TransactionalRef[T]

/**
 * Implements a transactional managed reference.
 * Alias to Ref.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalRef[T] extends Transactional {
  import org.multiverse.api.ThreadLocalTransaction._

  implicit val txInitName = "TransactionalRef:Init"
  val uuid = UUID.newUuid.toString

  private[this] lazy val ref: AlphaRef[T] = new AlphaRef

  def swap(elem: T) = {
    ensureIsInTransaction
    ref.set(elem)
  }

  def get: Option[T] = {
    ensureIsInTransaction
    if (ref.isNull) None
    else Some(ref.get)
  }

  def getOrWait: T = {
    ensureIsInTransaction
    ref.getOrAwait
  }

  def getOrElse(default: => T): T = {
    ensureIsInTransaction
    if (ref.isNull) default
    else ref.get
  }

  def isDefined: Boolean = {
    ensureIsInTransaction
    !ref.isNull
  }
  
  def isEmpty: Boolean = {
    ensureIsInTransaction
    ref.isNull
  }

  def map[B](f: T => B): Option[B] = {
    ensureIsInTransaction
    if (isEmpty) None else Some(f(ref.get))
  }

  def flatMap[B](f: T => Option[B]): Option[B] = {
    ensureIsInTransaction
    if (isEmpty) None else f(ref.get)
  }

  def filter(p: T => Boolean): Option[T] = {
    ensureIsInTransaction
    if (isEmpty || p(ref.get)) Some(ref.get) else None
  }

  def foreach(f: T => Unit) {
    ensureIsInTransaction
    if (!isEmpty) f(ref.get)
  }

  def elements: Iterator[T] = {
    ensureIsInTransaction
    if (isEmpty) Iterator.empty else Iterator(ref.get)
  }

  def toList: List[T] = {
    ensureIsInTransaction
    if (isEmpty) List() else List(ref.get)
  }

  def toRight[X](left: => X) = {
    ensureIsInTransaction
    if (isEmpty) Left(left) else Right(ref.get)
  }

  def toLeft[X](right: => X) = {
    ensureIsInTransaction
    if (isEmpty) Right(right) else Left(ref.get)
  }

  private def ensureIsInTransaction =
    if (getThreadLocalTransaction eq null) throw new NoTransactionInScopeException
}

object TransactionalMap {
  def apply[K, V]() = new TransactionalMap[K, V]
}

/**
 * Implements an in-memory transactional Map based on Clojure's PersistentMap.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalMap[K, V] extends Transactional with scala.collection.mutable.Map[K, V] {
  protected[this] val ref = TransactionalRef[HashTrie[K, V]]
  val uuid = UUID.newUuid.toString

  ref.swap(new HashTrie[K, V])
 
  def -=(key: K) = { 
    remove(key)
    this
  }

  def +=(key: K, value: V) = put(key, value)
  
  def +=(kv: (K, V)) = {
    put(kv._1,kv._2)
    this
  }

  override def remove(key: K) = {
    val map = ref.get.get
    val oldValue = map.get(key)
    ref.swap(ref.get.get - key)
    oldValue
  }

  def get(key: K): Option[V] = ref.get.get.get(key)
 
  override def put(key: K, value: V): Option[V] = {
    val map = ref.get.get
    val oldValue = map.get(key)
    ref.swap(map.update(key, value))
    oldValue
  }

  override def update(key: K, value: V) = {
    val map = ref.get.get
    val oldValue = map.get(key)
    ref.swap(map.update(key, value))
  }
  
  def iterator = ref.get.get.iterator

  override def elements: Iterator[(K, V)] = ref.get.get.iterator

  override def contains(key: K): Boolean = ref.get.get.contains(key)

  override def clear = ref.swap(new HashTrie[K, V])

  override def size: Int = ref.get.get.size
 
  override def hashCode: Int = System.identityHashCode(this);

  override def equals(other: Any): Boolean =
    other.isInstanceOf[TransactionalMap[_, _]] && 
    other.hashCode == hashCode
}

object TransactionalVector {
  def apply[T]() = new TransactionalVector[T]
}

/**
 * Implements an in-memory transactional Vector based on Clojure's PersistentVector.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalVector[T] extends Transactional with IndexedSeq[T] {
  val uuid = UUID.newUuid.toString

  private[this] val ref = TransactionalRef[Vector[T]]

  ref.swap(EmptyVector)
 
  def clear = ref.swap(EmptyVector)
  
  def +(elem: T) = add(elem)

  def add(elem: T) = ref.swap(ref.get.get + elem)

  def get(index: Int): T = ref.get.get.apply(index)

  /**
   * Removes the <i>tail</i> element of this vector.
   */
  def pop = ref.swap(ref.get.get.pop)

  def update(index: Int, elem: T) = ref.swap(ref.get.get.update(index, elem))

  def length: Int = ref.get.get.length

  def apply(index: Int): T = ref.get.get.apply(index)

  override def hashCode: Int = System.identityHashCode(this);

  override def equals(other: Any): Boolean = 
    other.isInstanceOf[TransactionalVector[_]] && 
    other.hashCode == hashCode
}

