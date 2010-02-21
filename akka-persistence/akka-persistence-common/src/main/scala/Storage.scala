/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.stm.TransactionManagement.currentTransaction
import se.scalablesolutions.akka.collection._
import se.scalablesolutions.akka.util.Logging

import org.codehaus.aspectwerkz.proxy.Uuid

class NoTransactionInScopeException extends RuntimeException

/**
 * Example Scala usage.
 * <p/>
 * New map with generated id.
 * <pre>
 * val myMap = CassandraStorage.newMap
 * </pre>
 *
 * New map with user-defined id.
 * <pre>
 * val myMap = MongoStorage.newMap(id)
 * </pre>
 *
 * Get map by user-defined id.
 * <pre>
 * val myMap = CassandraStorage.getMap(id)
 * </pre>
 * 
 * Example Java usage:
 * <pre>
 * PersistentMap<Object, Object> myMap = MongoStorage.newMap();
 * </pre>
 * Or:
 * <pre>
 * MongoPersistentMap myMap = MongoStorage.getMap(id);
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
trait Storage {
  type ElementType

  def newMap: PersistentMap[ElementType, ElementType]
  def newVector: PersistentVector[ElementType]
  def newRef: PersistentRef[ElementType]
  def newQueue: PersistentQueue[ElementType] = // only implemented for redis
    throw new UnsupportedOperationException

  def getMap(id: String): PersistentMap[ElementType, ElementType]
  def getVector(id: String): PersistentVector[ElementType]
  def getRef(id: String): PersistentRef[ElementType]
  def getQueue(id: String): PersistentQueue[ElementType] = // only implemented for redis
    throw new UnsupportedOperationException

  def newMap(id: String): PersistentMap[ElementType, ElementType]
  def newVector(id: String): PersistentVector[ElementType]
  def newRef(id: String): PersistentRef[ElementType]
  def newQueue(id: String): PersistentQueue[ElementType] = // only implemented for redis
    throw new UnsupportedOperationException
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
trait PersistentMap[K, V] extends scala.collection.mutable.Map[K, V]
  with Transactional with Committable with Logging {
  protected val newAndUpdatedEntries = TransactionalState.newMap[K, V]
  protected val removedEntries = TransactionalState.newVector[K]
  protected val shouldClearOnCommit = TransactionalRef[Boolean]()

  // to be concretized in subclasses
  val storage: MapStorageBackend[K, V]

  def commit = {
    removedEntries.toList.foreach(key => storage.removeMapStorageFor(uuid, key))
    storage.insertMapStorageEntriesFor(uuid, newAndUpdatedEntries.toList)
    if (shouldClearOnCommit.isDefined && shouldClearOnCommit.get.get)
      storage.removeMapStorageFor(uuid)
    newAndUpdatedEntries.clear
    removedEntries.clear
  }

  def -=(key: K) = {
    remove(key)
    this
  }

  override def +=(kv : (K,V)) = {
    put(kv._1,kv._2)
    this
  }

  def +=(key: K, value: V) = {
    put(key, value)
    this
  }
  
  override def put(key: K, value: V): Option[V] = {
    register
    newAndUpdatedEntries.put(key, value)
  }
 
  override def update(key: K, value: V) = { 
    register
    newAndUpdatedEntries.update(key, value)
  }
  
  override def remove(key: K) = {
    register
    removedEntries.add(key)
    newAndUpdatedEntries.get(key)
  }
  
  def slice(start: Option[K], count: Int): List[Tuple2[K, V]] =
    slice(start, None, count)

  def slice(start: Option[K], finish: Option[K], count: Int): List[Tuple2[K, V]] = try {
    storage.getMapStorageRangeFor(uuid, start, finish, count)
  } catch { case e: Exception => Nil }

  override def clear = { 
    register
    shouldClearOnCommit.swap(true)
  }
  
  override def contains(key: K): Boolean = try {
    newAndUpdatedEntries.contains(key) ||
    storage.getMapStorageEntryFor(uuid, key).isDefined
  } catch { case e: Exception => false }

  override def size: Int = try {
    storage.getMapStorageSizeFor(uuid)
  } catch { case e: Exception => 0 }

  override def get(key: K): Option[V] = {
    if (newAndUpdatedEntries.contains(key)) {
      newAndUpdatedEntries.get(key)
    }
    else try {
      storage.getMapStorageEntryFor(uuid, key)
    } catch { case e: Exception => None }
  }
  
  def iterator = elements
  
  override def elements: Iterator[Tuple2[K, V]]  = {
    new Iterator[Tuple2[K, V]] {
      private val originalList: List[Tuple2[K, V]] = try {
        storage.getMapStorageFor(uuid)
      } catch {
        case e: Throwable => Nil
      }
      private var elements = newAndUpdatedEntries.toList union originalList.reverse 
      override def next: Tuple2[K, V]= synchronized {
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
 * Implements a template for a concrete persistent transactional vector based storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentVector[T] extends RandomAccessSeq[T] with Transactional with Committable {
  protected val newElems = TransactionalState.newVector[T]
  protected val updatedElems = TransactionalState.newMap[Int, T]
  protected val removedElems = TransactionalState.newVector[T]
  protected val shouldClearOnCommit = TransactionalRef[Boolean]()

  val storage: VectorStorageBackend[T]

  def commit = {
    for (element <- newElems) storage.insertVectorStorageEntryFor(uuid, element)
    for (entry <- updatedElems) storage.updateVectorStorageEntryFor(uuid, entry._1, entry._2)
    newElems.clear
    updatedElems.clear
  }

  def +(elem: T) = add(elem)
  
  def add(elem: T) = {
    register
    newElems + elem
  }
 
  def apply(index: Int): T = get(index)

  def get(index: Int): T = {
    if (newElems.size > index) newElems(index)
    else storage.getVectorStorageEntryFor(uuid, index)
  }

  override def slice(start: Int, count: Int): RandomAccessSeq[T] = slice(Some(start), None, count)
  
  def slice(start: Option[Int], finish: Option[Int], count: Int): RandomAccessSeq[T] = {
    val buffer = new scala.collection.mutable.ArrayBuffer[T]
    storage.getVectorStorageRangeFor(uuid, start, finish, count).foreach(buffer.append(_))
    buffer
  }

  /**
   * Removes the <i>tail</i> element of this vector.
   */
  def pop: T = {
    register
    throw new UnsupportedOperationException("PersistentVector::pop is not implemented")
  }

  def update(index: Int, newElem: T) = {
    register
    storage.updateVectorStorageEntryFor(uuid, index, newElem)
  }

  override def first: T = get(0)

  override def last: T = {
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
 * Implements a persistent reference with abstract storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentRef[T] extends Transactional with Committable {
  protected val ref = new TransactionalRef[T]
  
  val storage: RefStorageBackend[T]

  def commit = if (ref.isDefined) {
    storage.insertRefStorageFor(uuid, ref.get.get)
    ref.swap(null.asInstanceOf[T]) 
  }

  def swap(elem: T) = {
    register
    ref.swap(elem)
  }
  
  def get: Option[T] = if (ref.isDefined) ref.get else storage.getRefStorageFor(uuid)

  def isDefined: Boolean = ref.isDefined || storage.getRefStorageFor(uuid).isDefined

  def getOrElse(default: => T): T = {
    val current = get
    if (current.isDefined) current.get
    else default
  }

  private def register = {
    if (currentTransaction.get.isEmpty) throw new NoTransactionInScopeException
    currentTransaction.get.get.register(uuid, this)
  }
}

/**
 * Implementation of <tt>PersistentQueue</tt> for every concrete 
 * storage will have the same workflow. This abstracts the workflow.
 * <p/>
 * Enqueue is simpler, we just have to record the operation in a local
 * transactional store for playback during commit. This store
 * <tt>enqueueNDequeuedEntries</tt> stores the entire history of enqueue
 * and dequeue that will be played at commit on the underlying store.
 * </p>
 * The main challenge with dequeue is that we need to return the element
 * that has been dequeued. Hence in addition to the above store, we need to
 * have another local queue that actually does the enqueue dequeue operations
 * that take place <em>only during this transaction</em>. This gives us the
 * element that will be dequeued next from the set of elements enqueued
 * <em>during this transaction</em>.
 * </p>
 * The third item that we need is an index to the underlying storage element
 * that may also have to be dequeued as part of the current transaction. This
 * is modeled using a ref to an Int that points to elements in the underlyinng store.
 * </p>
 * Subclasses just need to provide the actual concrete instance for the
 * abstract val <tt>storage</tt>.
 *
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
trait PersistentQueue[A] extends scala.collection.mutable.Queue[A]
  with Transactional with Committable with Logging {

  abstract case class QueueOp
  case object ENQ extends QueueOp
  case object DEQ extends QueueOp

  import scala.collection.immutable.Queue

  // current trail that will be played on commit to the underlying store
  protected val enqueuedNDequeuedEntries = TransactionalState.newVector[(Option[A], QueueOp)]
  protected val shouldClearOnCommit = TransactionalRef[Boolean]()

  // local queue that will record all enqueues and dequeues in the current txn
  protected val localQ = TransactionalRef[Queue[A]]()

  // keeps a pointer to the underlying storage for the enxt candidate to be dequeued
  protected val pickMeForDQ = TransactionalRef[Int]()

  localQ.swap(Queue.Empty)
  pickMeForDQ.swap(0)

  // to be concretized in subclasses
  val storage: QueueStorageBackend[A]

  def commit = {
    enqueuedNDequeuedEntries.toList.foreach { e =>
      e._2 match {
        case ENQ => storage.enqueue(uuid, e._1.get)
        case DEQ => storage.dequeue(uuid)
      }
    }
    if (shouldClearOnCommit.isDefined && shouldClearOnCommit.get.get) {
      storage.remove(uuid)
    }
    enqueuedNDequeuedEntries.clear
    localQ.swap(Queue.Empty)
    pickMeForDQ.swap(0)
  }

  override def enqueue(elems: A*) {
    register
    elems.foreach(e => {
      enqueuedNDequeuedEntries.add((Some(e), ENQ))
      localQ.get.get.enqueue(e)
    })
  }

  override def dequeue: A = {
    register
    // record for later playback
    enqueuedNDequeuedEntries.add((None, DEQ))

    val i = pickMeForDQ.get.get
    if (i < storage.size(uuid)) {
      // still we can DQ from storage
      pickMeForDQ.swap(i + 1)
      storage.peek(uuid, i, 1)(0)
    } else {
      // check we have transient candidates in localQ for DQ
      if (localQ.get.get.isEmpty == false) {
        val (a, q) = localQ.get.get.dequeue
        localQ.swap(q)
        a
      }
      else 
        throw new NoSuchElementException("trying to dequeue from empty queue")
    }
  }

  override def clear = { 
    register
    shouldClearOnCommit.swap(true)
    localQ.swap(Queue.Empty)
    pickMeForDQ.swap(0)
  }
  
  override def size: Int = try {
    storage.size(uuid) + localQ.get.get.length
  } catch { case e: Exception => 0 }

  override def isEmpty: Boolean =
    size == 0

  override def +=(elem: A) = { 
    enqueue(elem)
    this
  }
  override def ++=(elems: Iterator[A]) = { 
    enqueue(elems.toList: _*)
    this
  }
  def ++=(elems: Iterable[A]): Unit = this ++= elems.elements

  override def dequeueFirst(p: A => Boolean): Option[A] =
    throw new UnsupportedOperationException("dequeueFirst not supported")

  override def dequeueAll(p: A => Boolean): scala.collection.mutable.Seq[A] =
    throw new UnsupportedOperationException("dequeueAll not supported")

  private def register = {
    if (currentTransaction.get.isEmpty) throw new NoTransactionInScopeException
    currentTransaction.get.get.register(uuid, this)
  }
}
