/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.common

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.stm.TransactionManagement.transaction
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.AkkaException

class StorageException(message: String) extends AkkaException(message)

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
  def newSortedSet: PersistentSortedSet[ElementType] = // only implemented for redis
    throw new UnsupportedOperationException

  def getMap(id: String): PersistentMap[ElementType, ElementType]
  def getVector(id: String): PersistentVector[ElementType]
  def getRef(id: String): PersistentRef[ElementType]
  def getQueue(id: String): PersistentQueue[ElementType] = // only implemented for redis
    throw new UnsupportedOperationException
  def getSortedSet(id: String): PersistentSortedSet[ElementType] = // only implemented for redis
    throw new UnsupportedOperationException

  def newMap(id: String): PersistentMap[ElementType, ElementType]
  def newVector(id: String): PersistentVector[ElementType]
  def newRef(id: String): PersistentRef[ElementType]
  def newQueue(id: String): PersistentQueue[ElementType] = // only implemented for redis
    throw new UnsupportedOperationException
  def newSortedSet(id: String): PersistentSortedSet[ElementType] = // only implemented for redis
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
  with Transactional with Committable with Abortable with Logging {
  protected val newAndUpdatedEntries = TransactionalMap[K, V]()
  protected val removedEntries = TransactionalVector[K]()
  protected val shouldClearOnCommit = Ref[Boolean]()

  // to be concretized in subclasses
  val storage: MapStorageBackend[K, V]

  def commit = {
    if (shouldClearOnCommit.isDefined && shouldClearOnCommit.get) storage.removeMapStorageFor(uuid)
    removedEntries.toList.foreach(key => storage.removeMapStorageFor(uuid, key))
    storage.insertMapStorageEntriesFor(uuid, newAndUpdatedEntries.toList)
    newAndUpdatedEntries.clear
    removedEntries.clear
  }

  def abort = {
    newAndUpdatedEntries.clear
    removedEntries.clear
    shouldClearOnCommit.swap(false)
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
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register(uuid, this)
  }
}

/**
 * Implements a template for a concrete persistent transactional vector based storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentVector[T] extends IndexedSeq[T] with Transactional with Committable with Abortable {
  protected val newElems = TransactionalVector[T]()
  protected val updatedElems = TransactionalMap[Int, T]()
  protected val removedElems = TransactionalVector[T]()
  protected val shouldClearOnCommit = Ref[Boolean]()

  val storage: VectorStorageBackend[T]

  def commit = {
    for (element <- newElems) storage.insertVectorStorageEntryFor(uuid, element)
    for (entry <- updatedElems) storage.updateVectorStorageEntryFor(uuid, entry._1, entry._2)
    newElems.clear
    updatedElems.clear
  }

  def abort = {
    newElems.clear
    updatedElems.clear
    removedElems.clear
    shouldClearOnCommit.swap(false)
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

  override def slice(start: Int, finish: Int): IndexedSeq[T] = slice(Some(start), Some(finish))

  def slice(start: Option[Int], finish: Option[Int], count: Int = 0): IndexedSeq[T] = {
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
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register(uuid, this)
  }
}

/**
 * Implements a persistent reference with abstract storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentRef[T] extends Transactional with Committable with Abortable {
  protected val ref = Ref[T]()

  val storage: RefStorageBackend[T]

  def commit = if (ref.isDefined) {
    storage.insertRefStorageFor(uuid, ref.get)
    ref.swap(null.asInstanceOf[T])
  }

  def abort = ref.swap(null.asInstanceOf[T])

  def swap(elem: T) = {
    register
    ref.swap(elem)
  }

  def get: Option[T] = if (ref.isDefined) ref.opt else storage.getRefStorageFor(uuid)

  def isDefined: Boolean = ref.isDefined || storage.getRefStorageFor(uuid).isDefined

  def getOrElse(default: => T): T = {
    val current = get
    if (current.isDefined) current.get
    else default
  }

  private def register = {
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register(uuid, this)
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
  with Transactional with Committable with Abortable with Logging {

  sealed trait QueueOp
  case object ENQ extends QueueOp
  case object DEQ extends QueueOp

  import scala.collection.immutable.Queue

  // current trail that will be played on commit to the underlying store
  protected val enqueuedNDequeuedEntries = TransactionalVector[(Option[A], QueueOp)]()
  protected val shouldClearOnCommit = Ref[Boolean]()

  // local queue that will record all enqueues and dequeues in the current txn
  protected val localQ = Ref[Queue[A]]()

  // keeps a pointer to the underlying storage for the enxt candidate to be dequeued
  protected val pickMeForDQ = Ref[Int]()

  localQ.swap(Queue.empty)
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
    if (shouldClearOnCommit.isDefined && shouldClearOnCommit.get) {
      storage.remove(uuid)
    }
    enqueuedNDequeuedEntries.clear
    localQ.swap(Queue.empty)
    pickMeForDQ.swap(0)
    shouldClearOnCommit.swap(false)
  }

  def abort = {
    enqueuedNDequeuedEntries.clear
    shouldClearOnCommit.swap(false)
    localQ.swap(Queue.empty)
    pickMeForDQ.swap(0)
  }


  override def enqueue(elems: A*) {
    register
    elems.foreach(e => {
      enqueuedNDequeuedEntries.add((Some(e), ENQ))
      localQ.get.enqueue(e)
    })
  }

  override def dequeue: A = {
    register
    // record for later playback
    enqueuedNDequeuedEntries.add((None, DEQ))

    val i = pickMeForDQ.get
    if (i < storage.size(uuid)) {
      // still we can DQ from storage
      pickMeForDQ.swap(i + 1)
      storage.peek(uuid, i, 1)(0)
    } else {
      // check we have transient candidates in localQ for DQ
      if (localQ.get.isEmpty == false) {
        val (a, q) = localQ.get.dequeue
        localQ.swap(q)
        a
      } else throw new NoSuchElementException("trying to dequeue from empty queue")
    }
  }

  override def clear = {
    register
    shouldClearOnCommit.swap(true)
    localQ.swap(Queue.empty)
    pickMeForDQ.swap(0)
  }

  override def size: Int = try {
    storage.size(uuid) + localQ.get.length
  } catch { case e: Exception => 0 }

  override def isEmpty: Boolean =
    size == 0

  override def +=(elem: A) = {
    enqueue(elem)
    this
  }
  def ++=(elems: Iterator[A]) = {
    enqueue(elems.toList: _*)
    this
  }
  def ++=(elems: Iterable[A]): Unit = this ++= elems.iterator

  override def dequeueFirst(p: A => Boolean): Option[A] =
    throw new UnsupportedOperationException("dequeueFirst not supported")

  override def dequeueAll(p: A => Boolean): scala.collection.mutable.Seq[A] =
    throw new UnsupportedOperationException("dequeueAll not supported")

  private def register = {
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register(uuid, this)
  }
}

/**
 * Implements a template for a concrete persistent transactional sorted set based storage.
 * <p/>
 * Sorting is done based on a <i>zscore</i>. But the computation of zscore has been kept
 * outside the abstraction.
 * <p/>
 * zscore can be implemented in a variety of ways by the calling class:
 * <pre>
 * trait ZScorable {
 *   def toZScore: Float
 * }
 *
 * class Foo extends ZScorable {
 *   //.. implemnetation
 * }
 * </pre>
 * Or we can also use views:
 * <pre>
 * class Foo {
 *   //..
 * }
 *
 * implicit def Foo2Scorable(foo: Foo): ZScorable = new ZScorable {
 *   def toZScore = {
 *     //..
 *   }
 * }
 * </pre>
 *
 * and use <tt>foo.toZScore</tt> to compute the zscore and pass to the APIs.
 *
 * @author <a href="http://debasishg.blogspot.com"</a>
 */
trait PersistentSortedSet[A] extends Transactional with Committable with Abortable {

  protected val newElems = TransactionalMap[A, Float]()
  protected val removedElems = TransactionalVector[A]()

  val storage: SortedSetStorageBackend[A]

  def commit = {
    for ((element, score) <- newElems) storage.zadd(uuid, String.valueOf(score), element)
    for (element <- removedElems) storage.zrem(uuid, element)
    newElems.clear
    removedElems.clear
  }

  def abort = {
    newElems.clear
    removedElems.clear
  }

  def +(elem: A, score: Float) = add(elem, score)

  def add(elem: A, score: Float) = {
    register
    newElems.put(elem, score)
  }

  def -(elem: A) = remove(elem)

  def remove(elem: A) = {
    register
    removedElems.add(elem)
  }

  private def inStorage(elem: A): Option[Float] = storage.zscore(uuid, elem) match {
      case Some(s) => Some(s.toFloat)
      case None => None
  }

  def contains(elem: A): Boolean = {
    if (newElems contains elem) true
    else {
      inStorage(elem) match {
        case Some(f) => true
        case None => false
      }
    }
  }

  def size: Int = newElems.size + storage.zcard(uuid) - removedElems.size

  def zscore(elem: A): Float = {
    if (newElems contains elem) newElems.get(elem).get
    inStorage(elem) match {
      case Some(f) => f
      case None =>
        throw new NoSuchElementException(elem + " not present")
    }
  }

  implicit def order(x: (A, Float)) = new Ordered[(A, Float)] {
    def compare(that: (A, Float)) = x._2 compare that._2
  }

  implicit def ordering = new scala.math.Ordering[(A,Float)] {
    def compare(x: (A, Float),y : (A,Float)) = x._2 compare y._2
  }


  def zrange(start: Int, end: Int): List[(A, Float)] = {
    // need to operate on the whole range
    // get all from the underlying storage
    val fromStore = storage.zrangeWithScore(uuid, 0, -1)
    val ts = scala.collection.immutable.TreeSet(fromStore: _*) ++ newElems.toList
    val l = ts.size

    // -1 means the last element, -2 means the second last
    val s = if (start < 0) start + l else start
    val e =
      if (end < 0) end + l
      else if (end >= l) (l - 1)
      else end
    // slice is open at the end, we need a closed end range
    ts.iterator.slice(s, e + 1).toList
  }

  private def register = {
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register(uuid, this)
  }
}
