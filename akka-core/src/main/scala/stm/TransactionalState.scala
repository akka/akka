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
  def newMap[K, V](pairs: (K, V)*) = TransactionalMap(pairs: _*)

  def newVector[T] = TransactionalVector[T]()
  def newVector[T](elems: T*) = TransactionalVector(elems :_*)

  def newRef[T] = TransactionalRef[T]()
  def newRef[T](initialValue: T) = TransactionalRef(initialValue)
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
  type Ref[T] = TransactionalRef[T]

  def apply[T]() = new Ref[T]

  def apply[T](initialValue: T) = new Ref[T](Some(initialValue))
}

/**
 * Alias to Ref.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object TransactionalRef {

  /**
   * An implicit conversion that converts a TransactionalRef to an Iterable value.
   */
  implicit def ref2Iterable[T](ref: TransactionalRef[T]): Iterable[T] = ref.toList

  def apply[T]() = new TransactionalRef[T]

  def apply[T](initialValue: T) = new TransactionalRef[T](Some(initialValue))
}

/**
 * Implements a transactional managed reference.
 * Alias to Ref.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalRef[T](initialOpt: Option[T] = None) extends Transactional {
  self =>

  import org.multiverse.api.ThreadLocalTransaction._

  implicit val txInitName = "TransactionalRef:Init"
  val uuid = UUID.newUuid.toString

  private[this] val ref = {
    if (initialOpt.isDefined) new AlphaRef(initialOpt.get)
    else new AlphaRef[T]
  }

  def swap(elem: T) = {
    ensureIsInTransaction
    ref.set(elem)
  }

  def alter(f: T => T): T = {
    ensureIsInTransaction
    ensureNotNull
    ref.set(f(ref.get))
    ref.get
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

  def map[B](f: T => B): TransactionalRef[B] = {
    ensureIsInTransaction
    if (isEmpty) TransactionalRef[B] else TransactionalRef(f(ref.get))
  }

  def flatMap[B](f: T => TransactionalRef[B]): TransactionalRef[B] = {
    ensureIsInTransaction
    if (isEmpty) TransactionalRef[B] else f(ref.get)
  }

  def filter(p: T => Boolean): TransactionalRef[T] = {
    ensureIsInTransaction
    if (isDefined && p(ref.get)) TransactionalRef(ref.get) else TransactionalRef[T]
  }

  /**
   * Necessary to keep from being implicitly converted to Iterable in for comprehensions.
   */
  def withFilter(p: T => Boolean): WithFilter = new WithFilter(p)
	
  class WithFilter(p: T => Boolean) {
    def map[B](f: T => B): TransactionalRef[B] = self filter p map f
    def flatMap[B](f: T => TransactionalRef[B]): TransactionalRef[B] = self filter p flatMap f
    def foreach[U](f: T => U): Unit = self filter p foreach f
    def withFilter(q: T => Boolean): WithFilter = new WithFilter(x => p(x) && q(x))
  }

  def foreach[U](f: T => U): Unit = {
    ensureIsInTransaction
    if (isDefined) f(ref.get)
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

  private def ensureNotNull =
    if (ref.isNull) throw new RuntimeException("Cannot alter Ref's value when it is null")
}

object TransactionalMap {
  def apply[K, V]() = new TransactionalMap[K, V]

  def apply[K, V](pairs: (K, V)*) = new TransactionalMap(Some(HashTrie(pairs: _*)))
}

/**
 * Implements an in-memory transactional Map based on Clojure's PersistentMap.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalMap[K, V](initialOpt: Option[HashTrie[K, V]] = None) extends Transactional with scala.collection.mutable.Map[K, V] {
  val uuid = UUID.newUuid.toString

  protected[this] val ref = new TransactionalRef(initialOpt.orElse(Some(new HashTrie[K, V])))
 
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

  override def toString = if (outsideTransaction) "<TransactionalMap>" else super.toString
  
  def outsideTransaction =
    org.multiverse.api.ThreadLocalTransaction.getThreadLocalTransaction eq null
}

object TransactionalVector {
  def apply[T]() = new TransactionalVector[T]

  def apply[T](elems: T*) = new TransactionalVector(Some(Vector(elems: _*)))
}

/**
 * Implements an in-memory transactional Vector based on Clojure's PersistentVector.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalVector[T](initialOpt: Option[Vector[T]] = None) extends Transactional with IndexedSeq[T] {
  val uuid = UUID.newUuid.toString

  private[this] val ref = new TransactionalRef(initialOpt.orElse(Some(EmptyVector)))
 
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

  override def toString = if (outsideTransaction) "<TransactionalVector>" else super.toString
  
  def outsideTransaction =
    org.multiverse.api.ThreadLocalTransaction.getThreadLocalTransaction eq null
}

