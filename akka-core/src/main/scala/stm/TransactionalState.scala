/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.stm.Transaction.atomic
import se.scalablesolutions.akka.collection._

import org.multiverse.datastructures.refs.manual.Ref;

import org.codehaus.aspectwerkz.proxy.Uuid

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
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalRef[T] extends Transactional {
  implicit val txInitName = "TransactionalRef:Init"
  import org.multiverse.api.ThreadLocalTransaction._
  val uuid = Uuid.newUuid.toString

  private[this] val ref: Ref[T] = atomic { new Ref }

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
  
  def isEmpty: Boolean = ref.isNull

  def map[B](f: T => B): Option[B] = if (isEmpty) None else Some(f(ref.get))

  def flatMap[B](f: T => Option[B]): Option[B] = if (isEmpty) None else f(ref.get)

  def filter(p: T => Boolean): Option[T] = if (isEmpty || p(ref.get)) Some(ref.get) else None

  def foreach(f: T => Unit) { if (!isEmpty) f(ref.get) }

  def elements: Iterator[T] = if (isEmpty) Iterator.empty else Iterator.fromValues(ref.get)

  def toList: List[T] = if (isEmpty) List() else List(ref.get)

  def toRight[X](left: => X) = if (isEmpty) Left(left) else Right(ref.get)

  def toLeft[X](right: => X) = if (isEmpty) Right(right) else Left(ref.get)
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
  val uuid = Uuid.newUuid.toString

  ref.swap(new HashTrie[K, V])
 
  def -=(key: K) = remove(key)

  def +=(key: K, value: V) = put(key, value)

  def remove(key: K) = ref.swap(ref.get.get - key)

  def get(key: K): Option[V] = ref.get.get.get(key)
 
  override def put(key: K, value: V): Option[V] = {
    val map = ref.get.get
    val oldValue = map.get(key)
    ref.swap(map.update(key, value))
    oldValue
  }

  def update(key: K, value: V) = {
    val map = ref.get.get
    val oldValue = map.get(key)
    ref.swap(map.update(key, value))
  }

  def elements: Iterator[(K, V)] = ref.get.get.elements

  override def contains(key: K): Boolean = ref.get.get.contains(key)

  override def clear = ref.swap(new HashTrie[K, V])

  def size: Int = ref.get.get.size
 
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
class TransactionalVector[T] extends Transactional with RandomAccessSeq[T] {
  val uuid = Uuid.newUuid.toString

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

