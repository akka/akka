/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

//import org.multiverse.datastructures.refs.manual.Ref
import stm.{TransactionManagement, Ref}
import org.multiverse.templates.AtomicTemplate
import org.multiverse.api.Transaction;
import akka.collection._

import org.codehaus.aspectwerkz.proxy.Uuid

import scala.collection.mutable.{ArrayBuffer, HashMap}

/**
 * Scala API.
 * <p/>
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
 */
object TransactionalState extends TransactionalState

/**
 * Java API.
 * <p/>
 * Example Java usage:
 * <pre>
 * TransactionalState state = new TransactionalState();
 * TransactionalMap myMap = state.newMap();
 * </pre>
 */
class TransactionalState {
  def newMap[K, V] = {
//    new AtomicTemplate[TransactionalMap[K, V]]() {
//      def execute(t: Transaction): TransactionalMap[K, V] = {
        new TransactionalMap[K, V]
//      }
//    }.execute()
  }
  def newVector[T] = {
//    new AtomicTemplate[TransactionalVector[T]]() {
//      def execute(t: Transaction): TransactionalVector[T] = {
        new TransactionalVector[T]
//      }
//    }.execute()
  }
  def newRef[T] = {
//    new AtomicTemplate[TransactionalRef[T]]() {
//      def execute(t: Transaction): TransactionalRef[T] = {
        new TransactionalRef[T]
//      }
//    }.execute()
  }
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@serializable
trait Transactional {
  // FIXME: won't work across the cluster
  val uuid = Uuid.newUuid.toString
}

/**
 * Implements a transactional managed reference.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 *
class TransactionalRef[T] extends Transactional {
  protected[this] var ref: Option[Ref[T]] = None
 
  def set(elem: T) = swap(elem)
 
  def swap(elem: T) = {
    synchronized { if (ref.isEmpty) ref = Some(new Ref[T]) }
    ref.get.set(elem)
  }

  def get: Option[T] = 
    if (isEmpty) None
    else Some(ref.get.get)
 
  def getOrWait: T = {
    synchronized { if (ref.isEmpty) ref = Some(new Ref[T]) }
    ref.get.getOrAwait
  }

  def getOrElse(default: => T): T = 
    if (isEmpty) default
    else ref.get.get
 
  def isDefined: Boolean = ref.isDefined //&& !ref.get.isNull

  def isEmpty: Boolean = !isDefined
}

object TransactionalRef {
  def apply[T](elem: T) = {
    if (elem == null) throw new IllegalArgumentException("Can't define TransactionalRef with a null initial value, needs to be a PersistentDataStructure")
    val ref = new TransactionalRef[T]
    ref.swap(elem)
    ref
  }
}
*/
/**
 * Implements an in-memory transactional Map based on Clojure's PersistentMap.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalMap[K, V] extends Transactional with scala.collection.mutable.Map[K, V] {
  protected[this] val ref = TransactionalRef[HashTrie[K, V]]
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

object TransactionalMap {
  def apply[K, V]() = new TransactionalMap[K, V]
}

/**
 * Implements an in-memory transactional Vector based on Clojure's PersistentVector.
 *
 * Not thread-safe, but should only be using from within an Actor, e.g. one single thread at a time.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class TransactionalVector[T] extends Transactional with RandomAccessSeq[T] {
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

object TransactionalVector {
  def apply[T]() = new TransactionalVector
}

class TransactionalRef[T] extends Transactional {
  private[this] val ref = new Ref[T]

  def swap(elem: T) =
    try { ref.set(elem) } catch { case e: org.multiverse.api.exceptions.LoadTooOldVersionException => ref.set(elem) }


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
}

object TransactionalRef {
  def apply[T]() = {
//    new AtomicTemplate[TransactionalRef[T]]() {
//      def execute(t: Transaction): TransactionalRef[T] = {
        new TransactionalRef[T]
//      }
//    }.execute()
  }
}


