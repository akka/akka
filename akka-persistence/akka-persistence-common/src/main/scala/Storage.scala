/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.common

import akka.stm._
import akka.stm.TransactionManagement.transaction
import akka.util.Logging
import akka.japi.{Option => JOption}
import collection.mutable.ArraySeq
import akka.serialization.Serializer

// FIXME move to 'stm' package + add message with more info
class NoTransactionInScopeException extends RuntimeException

class StorageException(message: String) extends RuntimeException(message)

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

  def transactional:Boolean=false

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

private[akka] object PersistentMap {
  // operations on the Map
  sealed trait Op
  case object PUT extends Op
  case object REM extends Op
  case object UPD extends Op
  case object CLR extends Op
}

private[akka] trait ExecutableEntry{
    private var unexec = true
    def wasExecuted() = {unexec = false}
    def notExecuted() = unexec
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
        with Transactional with Committable with Abortable with Recoverable with Logging {

  def asJava() : java.util.Map[K, V] = scala.collection.JavaConversions.asMap(this)

  //Import Ops
  import PersistentMap._

  // append only log: records all mutating operations
  protected val appendOnlyTxLog = TransactionalVector[LogEntry]()

  case class LogEntry(key: Option[K], value: Option[V], op: Op) extends ExecutableEntry

  // need to override in subclasses e.g. "sameElements" for Array[Byte]
  def equal(k1: K, k2: K): Boolean = k1 == k2

  // Seqable type that's required for maintaining the log of distinct keys affected in current transaction
  type T <: Equals

  // converts key K to the Seqable type Equals
  def toEquals(k: K): T

  // keys affected in the current transaction
  protected val keysInCurrentTx = TransactionalMap[T, K]()

  protected def addToListOfKeysInTx(key: K): Unit =
    keysInCurrentTx += (toEquals(key), key)

  protected def clearDistinctKeys = keysInCurrentTx.clear

  protected def filterTxLogByKey(key: K): IndexedSeq[LogEntry] =
    appendOnlyTxLog filter (e => e.key.map(equal(_, key)).getOrElse(true))

  // need to get current value considering the underlying storage as well as the transaction log
  protected def getCurrentValue(key: K): Option[V] = {

    // get all mutating entries for this key for this tx
    val txEntries = filterTxLogByKey(key)

    // get the snapshot from the underlying store for this key
    val underlying = try {
      storage.getMapStorageEntryFor(uuid, key)
    } catch {case e: Exception => None}

    if (txEntries.isEmpty) underlying
    else txEntries.last match {
      case LogEntry(_, _, CLR) => None
      case _ => replay(txEntries, key, underlying)
    }
  }

  // replay all tx entries for key k with seed = initial
  private def replay(txEntries: IndexedSeq[LogEntry], key: K, initial: Option[V]): Option[V] = {
    import scala.collection.mutable._

    val m = initial match {
      case None => Map.empty[K, V]
      case Some(v) => Map((key, v))
    }
    txEntries.foreach {
      case LogEntry(k, v, o) => o match {
        case PUT => m.put(k.get, v.get)
        case REM => m -= k.get
        case UPD => m.update(k.get, v.get)
        case CLR => Map.empty[K, V]
      }
    }
    m get key
  }

  // to be concretized in subclasses
  val storage: MapStorageBackend[K, V]

  def commit = {
    appendOnlyTxLog.foreach{
      entry =>
        if (storage.transactional || entry.notExecuted) {
          entry match {
            case LogEntry(k, v, o) => {
              o match {
                case PUT => storage.insertMapStorageEntryFor(uuid, k.get, v.get)
                case UPD => storage.insertMapStorageEntryFor(uuid, k.get, v.get)
                case REM => storage.removeMapStorageFor(uuid, k.get)
                case CLR => storage.removeMapStorageFor(uuid)
              }
              entry.wasExecuted
            }
          }
        }
    }
    appendOnlyTxLog.clear
    clearDistinctKeys
  }

  def abort = {
    appendOnlyTxLog.clear
    clearDistinctKeys
  }

  def -=(key: K) = {
    remove(key)
    this
  }

  override def +=(kv: (K, V)) = {
    put(kv._1, kv._2)
    this
  }

  def +=(key: K, value: V) = {
    put(key, value)
    this
  }

  override def put(key: K, value: V): Option[V] = {
    register
    val curr = getCurrentValue(key)
    appendOnlyTxLog add LogEntry(Some(key), Some(value), PUT)
    addToListOfKeysInTx(key)
    curr
  }

  override def update(key: K, value: V) {
    register
    val curr = getCurrentValue(key)
    appendOnlyTxLog add LogEntry(Some(key), Some(value), UPD)
    addToListOfKeysInTx(key)
    curr
  }

  override def remove(key: K) : Option[V] = {
    register
    val curr = getCurrentValue(key)
    appendOnlyTxLog add LogEntry(Some(key), None, REM)
    addToListOfKeysInTx(key)
    curr
  }

  def slice(start: Option[K], count: Int): List[(K, V)] =
    slice(start, None, count)

  def slice(start: Option[K], finish: Option[K], count: Int): List[(K, V)]

  override def clear = {
    register
    appendOnlyTxLog add LogEntry(None, None, CLR)
    clearDistinctKeys
  }

  override def contains(key: K): Boolean = try {
    filterTxLogByKey(key) match {
      case Seq() => // current tx doesn't use this
        storage.getMapStorageEntryFor(uuid, key).isDefined // check storage
      case txs => // present in log
        val lastOp = txs.last.op
        lastOp != REM && lastOp != CLR // last entry cannot be a REM
    }
  } catch {case e: Exception => false}

  protected def existsInStorage(key: K): Option[V] = try {
    storage.getMapStorageEntryFor(uuid, key)
  } catch {
    case e: Exception => None
  }

  override def size: Int = try {
    // partition key set affected in current tx into those which r added & which r deleted
    val (keysAdded, keysRemoved) = keysInCurrentTx.map {
      case (kseq, k) => ((kseq, k), getCurrentValue(k))
    }.partition(_._2.isDefined)

    // keys which existed in storage but removed in current tx
    val inStorageRemovedInTx =
    keysRemoved.keySet
            .map(_._2)
            .filter(k => existsInStorage(k).isDefined)
            .size

    // all keys in storage
    val keysInStorage =
    storage.getMapStorageFor(uuid)
            .map {case (k, v) => toEquals(k)}
            .toSet

    // (keys that existed UNION keys added ) - (keys removed)
    (keysInStorage union keysAdded.keySet.map(_._1)).size - inStorageRemovedInTx
  } catch {
    case e: Exception => 0
  }

  // get must consider underlying storage & current uncommitted tx log
  override def get(key: K): Option[V] = getCurrentValue(key)

  def iterator: Iterator[Tuple2[K, V]]

  protected def register = {
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register("Map:" + uuid, this)
  }

  def applyLog(log: Array[Byte]) = {
    val tlog = Serializer.Java.fromBinary(log, Some(classOf[TransactionalVector[LogEntry]])).asInstanceOf[TransactionalVector[LogEntry]]
    tlog.foreach{
      appendOnlyTxLog add _
    }
  }

  def getLog() = Serializer.Java.toBinary(appendOnlyTxLog)
}

object PersistentMapBinary {
  object COrdering {
    //frontend
    implicit object ArraySeqOrdering extends Ordering[ArraySeq[Byte]] {
      def compare(o1: ArraySeq[Byte], o2: ArraySeq[Byte]) =
        ArrayOrdering.compare(o1.toArray, o2.toArray)
    }
    //backend

    implicit object ArrayOrdering extends Ordering[Array[Byte]] {
      import java.lang.{Math=>M}
      def compare(o1: Array[Byte], o2: Array[Byte]): Int = {
        if (o1.size == o2.size) {
          for (i <- 0 until o1.size) {
            var a = o1(i)
            var b = o2(i)
            if (a != b) {
              return (a - b) / (M.abs(a - b))
            }
          }
          0
        } else {
          (o1.length - o2.length) / (M.max(1, M.abs(o1.length - o2.length)))
        }
      }

    }

  }
}

trait PersistentMapBinary extends PersistentMap[Array[Byte], Array[Byte]] {
  import scala.collection.mutable.ArraySeq

  type T = ArraySeq[Byte]

  def toEquals(k: Array[Byte]) = ArraySeq(k: _*)

  override def equal(k1: Array[Byte], k2: Array[Byte]): Boolean = k1 sameElements k2



  import scala.collection.immutable.{TreeMap, SortedMap}
  private def replayAllKeys: SortedMap[ArraySeq[Byte], Array[Byte]] = {
    import PersistentMapBinary.COrdering._

    // need ArraySeq for ordering
    val fromStorage =
    TreeMap(storage.getMapStorageFor(uuid).map {case (k, v) => (ArraySeq(k: _*), v)}: _*)

    val (keysAdded, keysRemoved) = keysInCurrentTx.map {
      case (_, k) => (k, getCurrentValue(k))
    }.partition(_._2.isDefined)

    val inStorageRemovedInTx =
    keysRemoved.keySet
            .filter(k => existsInStorage(k).isDefined)
            .map(k => ArraySeq(k: _*))

    (fromStorage -- inStorageRemovedInTx) ++ keysAdded.map {case (k, v) => (ArraySeq(k: _*), v.get)}
  }

  override def slice(start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int): List[(Array[Byte], Array[Byte])] = try {
    val newMap = replayAllKeys

    if (newMap isEmpty) List[(Array[Byte], Array[Byte])]()

    val startKey =
    start match {
      case Some(bytes) => Some(ArraySeq(bytes: _*))
      case None => None
    }

    val endKey =
    finish match {
      case Some(bytes) => Some(ArraySeq(bytes: _*))
      case None => None
    }

    ((startKey, endKey, count): @unchecked) match {
      case ((Some(s), Some(e), _)) =>
        newMap.range(s, e)
                .toList
                .map(e => (e._1.toArray, e._2))
                .toList
      case ((Some(s), None, c)) if c > 0 =>
        newMap.from(s)
                .iterator
                .take(count)
                .map(e => (e._1.toArray, e._2))
                .toList
      case ((Some(s), None, _)) =>
        newMap.from(s)
                .toList
                .map(e => (e._1.toArray, e._2))
                .toList
      case ((None, Some(e), _)) =>
        newMap.until(e)
                .toList
                .map(e => (e._1.toArray, e._2))
                .toList
    }
  } catch {case e: Exception => Nil}

  override def iterator: Iterator[(Array[Byte], Array[Byte])] = {
    new Iterator[(Array[Byte], Array[Byte])] {
      private var elements = replayAllKeys

      override def next: (Array[Byte], Array[Byte]) = synchronized {
        val (k, v) = elements.head
        elements = elements.tail
        (k.toArray, v)
      }

      override def hasNext: Boolean = synchronized {!elements.isEmpty}
    }
  }

  /**
   * Java API.
   */
  def javaIterator: java.util.Iterator[java.util.Map.Entry[Array[Byte],Array[Byte]]]  = {
    new java.util.Iterator[java.util.Map.Entry[Array[Byte],Array[Byte]]] {
      private var elements = replayAllKeys
      override def next: java.util.Map.Entry[Array[Byte], Array[Byte]] = synchronized {
        val (k, v) = elements.head
        elements = elements.tail
        val entry = new java.util.Map.Entry[Array[Byte], Array[Byte]] {
          override def getKey = k.toArray
          override def getValue = v
          override def setValue(v: Array[Byte]) = throw new UnsupportedOperationException("Use put or update methods to change a map entry.")
        }
        entry
      }
      override def hasNext: Boolean = synchronized { !elements.isEmpty }
      override def remove: Unit = throw new UnsupportedOperationException("Use remove method to remove a map entry.")
    }
  }
}

private[akka] object PersistentVector {
  // operations on the Vector
  sealed trait Op
  case object ADD extends Op
  case object UPD extends Op
  case object POP extends Op
}

/**
 * Implements a template for a concrete persistent transactional vector based storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentVector[T] extends IndexedSeq[T] with Transactional with Committable with Abortable with Recoverable {
  //Import Ops
  import PersistentVector._

  def asJava() : java.util.List[T] = scala.collection.JavaConversions.asList(this)

  // append only log: records all mutating operations
  protected val appendOnlyTxLog = TransactionalVector[LogEntry]()

  case class LogEntry(index: Option[Int], value: Option[T], op: Op) extends ExecutableEntry

  // need to override in subclasses e.g. "sameElements" for Array[Byte]
  // def equal(v1: T, v2: T): Boolean = v1 == v2

  val storage: VectorStorageBackend[T]

  def commit = {
    for (entry <- appendOnlyTxLog) {
      if (storage.transactional || entry.notExecuted) {
        (entry: @unchecked) match {
          case LogEntry(_, Some(v), ADD) => storage.insertVectorStorageEntryFor(uuid, v)
          case LogEntry(Some(i), Some(v), UPD) => storage.updateVectorStorageEntryFor(uuid, i, v)
          case LogEntry(_, _, POP) => storage.removeVectorStorageEntryFor(uuid)
        }
        entry.wasExecuted
      }
    }
    appendOnlyTxLog.clear
  }

  def abort = {
    appendOnlyTxLog.clear
  }

  private def replay: List[T] = {
    import scala.collection.mutable.ArrayBuffer
    var elemsStorage = ArrayBuffer(storage.getVectorStorageRangeFor(uuid, None, None, storage.getVectorStorageSizeFor(uuid)).reverse: _*)

    for (entry <- appendOnlyTxLog) {
      (entry: @unchecked) match {
        case LogEntry(_, Some(v), ADD) => elemsStorage += v
        case LogEntry(Some(i), Some(v), UPD) => elemsStorage.update(i, v)
        case LogEntry(_, _, POP) => elemsStorage = elemsStorage.drop(1)
      }
    }
    elemsStorage.toList.reverse
  }

  def +(elem: T) = add(elem)

  def add(elem: T) = {
    register
    appendOnlyTxLog + LogEntry(None, Some(elem), ADD)
  }

  def apply(index: Int): T = get(index)

  def get(index: Int): T = {
    if (appendOnlyTxLog.isEmpty) {
      storage.getVectorStorageEntryFor(uuid, index)
    } else {
      val curr = replay
      curr(index)
    }
  }

  override def slice(start: Int, finish: Int): IndexedSeq[T] = slice(Some(start), Some(finish))

  def slice(start: Option[Int], finish: Option[Int], count: Int = 0): IndexedSeq[T] = {
    val curr = replay
    val s = if (start.isDefined) start.get else 0
    val cnt =
    if (finish.isDefined) {
      val f = finish.get
      if (f >= s) (f - s) else count
    }
    else count
    if (s == 0 && cnt == 0) List().toIndexedSeq
    else curr.slice(s, s + cnt).toIndexedSeq
  }

  /**
   * Removes the <i>tail</i> element of this vector.
   */
  def pop: T = {
      register
      val curr = replay
      appendOnlyTxLog + LogEntry(None, None, POP)
      curr.last
  }

  def update(index: Int, newElem: T) = {
    register
    appendOnlyTxLog + LogEntry(Some(index), Some(newElem), UPD)
  }

  override def first: T = get(0)

  override def last: T = replay.last

  def length: Int = replay.length

  protected def register = {
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register("Vector" + uuid, this)
  }

  def applyLog(log: Array[Byte]) = {
    val tlog = Serializer.Java.fromBinary(log, Some(classOf[TransactionalVector[LogEntry]])).asInstanceOf[TransactionalVector[LogEntry]]
    tlog.foreach{
      appendOnlyTxLog add _
    }
  }

  def getLog() = Serializer.Java.toBinary(appendOnlyTxLog)
}

/**
 * Implements a persistent reference with abstract storage.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait PersistentRef[T] extends Transactional with Committable with Abortable with Recoverable {
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

  protected def register = {
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register("Ref" + uuid, this)
  }

  def applyLog(log: Array[Byte]) = {
    ref.swap(Serializer.Java.fromBinary(log, Some(classOf[Ref[T]])).asInstanceOf[T])
  }

  def getLog() = Serializer.Java.toBinary(ref.get.asInstanceOf[AnyRef])
}

private[akka] object PersistentQueue {
  //Operations for PersistentQueue
  sealed trait QueueOp
  case object ENQ extends QueueOp
  case object DEQ extends QueueOp
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
        with Transactional with Committable with Abortable with Recoverable with Logging {

  //Import Ops
  import PersistentQueue._

  case class LogEntry(value: Option[A], op: QueueOp) extends ExecutableEntry

  // current trail that will be played on commit to the underlying store
  protected val appendOnlyTxLog = TransactionalVector[LogEntry]()

  // to be concretized in subclasses
  val storage: QueueStorageBackend[A]

  def commit = synchronized{
    for (entry <- appendOnlyTxLog) {
      if (storage.transactional || entry.notExecuted) {
        (entry: @unchecked) match {
          case LogEntry(Some(v), ENQ) => storage.enqueue(uuid, v)
          case LogEntry(_, DEQ) => storage.dequeue(uuid)
        }
        entry.wasExecuted
      }
    }
    appendOnlyTxLog.clear
  }

  def abort = synchronized {
    appendOnlyTxLog.clear
  }

  override def toList = replay

  override def enqueue(elems: A*) = synchronized {
    register
    elems.foreach(e => appendOnlyTxLog.add(LogEntry(Some(e), ENQ)))
  }

  private def replay: List[A] = synchronized {
    import scala.collection.mutable.ListBuffer
    var elemsStorage = ListBuffer(storage.peek(uuid, 0, storage.size(uuid)): _*)

    for (entry <- appendOnlyTxLog) {
      (entry: @unchecked) match {
        case LogEntry(Some(v), ENQ) => elemsStorage += v
        case LogEntry(_, DEQ) => elemsStorage = elemsStorage.drop(1)
      }
    }
    elemsStorage.toList
  }

  override def dequeue: A = synchronized {
    register
    val l = replay
    if (l.isEmpty) throw new NoSuchElementException("trying to dequeue from empty queue")
    appendOnlyTxLog.add(LogEntry(None, DEQ))
    l.head
  }

  override def clear = synchronized {
    register
    appendOnlyTxLog.clear
  }

  override def size: Int = try {
    replay.size
  } catch {case e: Exception => 0}

  override def isEmpty: Boolean = size == 0

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

  protected def register = {
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register("Queue:" + uuid, this)
  }

  def applyLog(log: Array[Byte]) = {
    val tlog = Serializer.Java.fromBinary(log, Some(classOf[TransactionalVector[LogEntry]])).asInstanceOf[TransactionalVector[LogEntry]]
    tlog.foreach{
      appendOnlyTxLog add _
    }
  }

  def getLog() = Serializer.Java.toBinary(appendOnlyTxLog)

}

private[akka] object PersistentSortedSet {
  // operations on the SortedSet
  sealed trait Op
  case object ADD extends Op
  case object REM extends Op
}

/**
 * Implements a template for a concrete persistent transactional sorted set based storage.
 * <p/>
 * Sorting is done based on a <i>zscore</i>. But the computation of zscore has been kept
 * outside the abstraction.
 * <p/>
 * zscore can be implemented in a variety of ways by the calling class:
 * <pre>
 * trait ZScorable        {
 *   def toZScore: Float
 * }
 *
 * class Foo extends ZScorable        {
 *   //.. implemnetation
 * }
 * </pre>
 * Or we can also use views:
 * <pre>
 * class Foo        {
 *   //..
 * }
 *
 * implicit def Foo2Scorable(foo: Foo): ZScorable = new ZScorable        {
 *   def toZScore =        {
 *     //..
 * }
 * }
 * </pre>
 *
 * and use <tt>foo.toZScore</tt> to compute the zscore and pass to the APIs.
 *
 * @author <a href="http://debasishg.blogspot.com"</a>
 */
trait PersistentSortedSet[A] extends Transactional with Committable with Abortable with Recoverable {
  //Import Ops
  import PersistentSortedSet._

  // append only log: records all mutating operations
  protected val appendOnlyTxLog = TransactionalVector[LogEntry]()

  // need to override in subclasses e.g. "sameElements" for Array[Byte]
  def equal(v1: A, v2: A): Boolean = v1 == v2

  case class LogEntry(value: A, score: Option[Float], op: Op) extends ExecutableEntry

  val storage: SortedSetStorageBackend[A]

  def commit = {
    for (entry <- appendOnlyTxLog) {
      if (storage.transactional || entry.notExecuted) {
        (entry: @unchecked) match {
          case LogEntry(e, Some(s), ADD) => storage.zadd(uuid, String.valueOf(s), e)
          case LogEntry(e, _, REM) => storage.zrem(uuid, e)
        }
        entry.wasExecuted
      }
    }
    appendOnlyTxLog.clear
  }

  def abort = {
    appendOnlyTxLog.clear
  }

  def +(elem: A, score: Float) = add(elem, score)

  def add(elem: A, score: Float) = {
    register
    appendOnlyTxLog.add(LogEntry(elem, Some(score), ADD))
  }

  def -(elem: A) = remove(elem)

  def remove(elem: A) = {
    register
    appendOnlyTxLog.add(LogEntry(elem, None, REM))
  }

  protected def replay: List[(A, Float)] = {
    val es = collection.mutable.Map() ++ storage.zrangeWithScore(uuid, 0, -1)

    for (entry <- appendOnlyTxLog) {
      (entry: @unchecked) match {
        case LogEntry(v, Some(s), ADD) => es += ((v, s))
        case LogEntry(v, _, REM) => es -= v
      }
    }
    es.toList
  }

  def contains(elem: A): Boolean = replay.map(_._1).contains(elem)

  def size: Int = replay size

  def zscore(elem: A): Float = replay.filter { case (e, s) => equal(e, elem) }.map(_._2).head

  def zrange(start: Int, end: Int): List[(A, Float)] = {
    import PersistentSortedSet._

    // easier would have been to use a TreeSet
    // problem is the treeset has to be ordered on the score
    // but we cannot kick out elements with duplicate score
    // But we need to treat the value (A) as set, i.e. replace duplicates with
    // the latest one, as par with the behavior of redis zrange
    val es = replay

    // a multimap with key as A and value as Set of scores
    val m = new collection.mutable.HashMap[A, collection.mutable.Set[Float]]
                with collection.mutable.MultiMap[A, Float]
    for(e <- es) m.addBinding(e._1, e._2)

    // another list for unique values
    val as = es.map(_._1).distinct

    // iterate the list of unique values and for each pick the head element
    // from the score map
    val ts = as.map(a => (a, m(a).head)).sortWith((a, b) => a._2 < b._2)
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

  protected def register = {
    if (transaction.get.isEmpty) throw new NoTransactionInScopeException
    transaction.get.get.register((this.getClass, uuid), this)
  }

  def applyLog(log: Array[Byte]) = {
    val tlog = Serializer.Java.fromBinary(log, Some(classOf[TransactionalVector[LogEntry]])).asInstanceOf[TransactionalVector[LogEntry]]
    tlog.foreach{
      appendOnlyTxLog add _
    }
  }

  def getLog() = Serializer.Java.toBinary(appendOnlyTxLog)
}

trait PersistentSortedSetBinary extends PersistentSortedSet[Array[Byte]] {
  import PersistentSortedSet._

  override def equal(k1: Array[Byte], k2: Array[Byte]): Boolean = k1 sameElements k2

  override protected def replay: List[(Array[Byte], Float)] = {
    val es = collection.mutable.Map() ++ storage.zrangeWithScore(uuid, 0, -1).map { case (k, v) => (ArraySeq(k: _*), v) }

    for (entry <- appendOnlyTxLog) {
      (entry: @unchecked) match {
        case LogEntry(v, Some(s), ADD) => es += ((ArraySeq(v: _*), s))
        case LogEntry(v, _, REM) => es -= ArraySeq(v: _*)
      }
    }
    es.toList.map { case (k, v) => (k.toArray, v) }
  }
}
