/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.common

import akka.util.Logging
import java.lang.String
import java.nio.ByteBuffer
import collection.Map
import java.util.{Map => JMap}
import akka.persistence.common.PersistentMapBinary.COrdering._
import collection.immutable._
import collection.mutable.ArrayBuffer


private[akka] trait CommonStorageBackendAccess {

  import CommonStorageBackend._

  /*abstract*/

  def get(owner: String, key: Array[Byte], default: Array[Byte]): Array[Byte]

  def getAll(owner: String, keys: Iterable[Array[Byte]]): Map[Array[Byte], Array[Byte]] = {
    keys.foldLeft(new HashMap[Array[Byte], Array[Byte]]) {
      (map, key) => {
        Option(get(owner, key)) match {
          case Some(value) => map + (key -> value)
          case None => map
        }
      }
    }
  }

  def put(owner: String, key: Array[Byte], value: Array[Byte]): Unit

  def putAll(owner: String, keyValues: Iterable[(Array[Byte], Array[Byte])]): Unit = {
    keyValues.foreach{
      kv => kv match {
        case (key, value) => put(owner, key, value)
      }
    }
  }

  def delete(owner: String, key: Array[Byte]): Unit

  def deleteAll(owner: String, keys: Iterable[Array[Byte]]): Unit = {
    keys.foreach(delete(owner, _))
  }

  def drop(): Unit

  /*concrete*/

  def decodeMapKey(owner: String, key: Array[Byte]): Array[Byte] = key

  def encodeMapKey(owner: String, key: Array[Byte]): Array[Byte] = key

  def decodeIndexedKey(owner: String, key: Array[Byte]): Int = IntSerializer.fromBytes(key)

  def encodeIndexedKey(owner: String, keyint: Int): Array[Byte] = IntSerializer.toBytes(keyint)

  def deleteIndexed(owner: String, index: Int): Unit = delete(owner, encodeIndexedKey(owner, index))

  def getIndexed(owner: String, index: Int): Array[Byte] = get(owner, encodeIndexedKey(owner, index))

  def get(owner: String, key: Array[Byte]): Array[Byte] = get(owner, key, null)

  def putIndexed(owner: String, index: Int, value: Array[Byte]): Unit = put(owner, encodeIndexedKey(owner, index), value)

  def putAllIndexed(owner: String, values: Iterable[(Int, Array[Byte])]): Unit = {
    putAll(owner, values.map{
      iv => {
        iv match {
          case (i, value) => (encodeIndexedKey(owner, i) -> value)
        }
      }
    })
  }

  def getAllIndexed(owner: String, keys: Iterable[Int]): Map[Int, Array[Byte]] = {
    val byteKeys = keys.map(encodeIndexedKey(owner, _))
    getAll(owner, byteKeys).map{
      kv => kv match {
        case (key, value) => (decodeIndexedKey(owner, key) -> value)
      }
    }
  }

  def deleteAllIndexed(owner: String, keys: Iterable[Int]): Unit = {
    val byteKeys = keys.map(encodeIndexedKey(owner, _))
    deleteAll(owner, byteKeys)
  }
}

private[akka] trait KVStorageBackendAccess extends CommonStorageBackendAccess with Logging {

  import CommonStorageBackend._
  import KVStorageBackend._

  def put(key: Array[Byte], value: Array[Byte]): Unit

  def get(key: Array[Byte]): Array[Byte]

  def get(key: Array[Byte], default: Array[Byte]): Array[Byte]

  def getAll(keys: Iterable[Array[Byte]]): Map[Array[Byte], Array[Byte]]

  def delete(key: Array[Byte]): Unit

  override def decodeMapKey(owner: String, key: Array[Byte]): Array[Byte] = {
    val mapKeyLength = key.length - IntSerializer.bytesPerInt - owner.getBytes("UTF-8").length
    val mapkey = new Array[Byte](mapKeyLength)
    System.arraycopy(key, key.length - mapKeyLength, mapkey, 0, mapKeyLength)
    mapkey
  }


  override def decodeIndexedKey(owner: String, key: Array[Byte]): Int = {
    IntSerializer.fromBytes(decodeMapKey(owner,key))
  }

  override def put(owner: String, key: Array[Byte], value: Array[Byte]): Unit = {
    put(getKey(owner, key), value)
  }

  override def putIndexed(owner: String, index: Int, value: Array[Byte]): Unit = {
    put(getIndexedKey(owner, index), value)
  }


  override def get(owner: String, key: Array[Byte]): Array[Byte] = {
    get(getKey(owner, key))
  }

  override def getIndexed(owner: String, index: Int): Array[Byte] = {
    get(getIndexedKey(owner, index))
  }


  override def get(owner: String, key: Array[Byte], default: Array[Byte]): Array[Byte] = {
    get(getKey(owner, key), default)
  }


  override def getAll(owner: String, keys: Iterable[Array[Byte]]): Map[Array[Byte], Array[Byte]] = {
    getAll(keys.map{
      getKey(owner, _)
    })
  }

  override def deleteIndexed(owner: String, index: Int): Unit = {
    delete(getIndexedKey(owner, index))
  }

  override def delete(owner: String, key: Array[Byte]): Unit = {
    delete(getKey(owner, key))
  }
}

private[akka] object CommonStorageBackendAccess {
  implicit def stringToByteArray(st: String): Array[Byte] = {
    st.getBytes("UTF-8")
  }
}

private[akka] object CommonStorageBackend {
  val nullMapValueHeader = 0x00.byteValue
  val nullMapValue: Array[Byte] = Array(nullMapValueHeader)
  val notNullMapValueHeader: Byte = 0xff.byteValue
  val mapKeySetKeyHeader = 0x00.byteValue
  val mapKeyHeader = 0xff.byteValue
  val mapKeysIndex: Array[Byte] = new Array[Byte](1).padTo(1, mapKeySetKeyHeader)
  val mapKeysWrapperPad: Array[Byte] = new Array[Byte](1).padTo(1, mapKeyHeader)

  /**
   * Wrap map key prepends mapKeysWrapperPad (1-byte) to map keys so that we can
   * use a seperate 1 byte key to store the map keyset.
   *
   * This basically creates the map key used in underlying storage
   */

  def wrapMapKey(key: Array[Byte]): Array[Byte] = {
    val wrapped = new Array[Byte](key.length + mapKeysWrapperPad.length)
    System.arraycopy(mapKeysWrapperPad, 0, wrapped, 0, mapKeysWrapperPad.length)
    System.arraycopy(key, 0, wrapped, mapKeysWrapperPad.length, key.length)
    wrapped
  }

  /**
   * unwrapMapKey removes the mapKeysWrapperPad, this translates the map key used
   * in underlying storage back to a key that is understandable by the frontend
   */

  def unwrapMapKey(key: Array[Byte]): Array[Byte] = {
    val unwrapped = new Array[Byte](key.length - mapKeysWrapperPad.length)
    System.arraycopy(key, mapKeysWrapperPad.length, unwrapped, 0, unwrapped.length)
    unwrapped
  }

  def getStoredMapValue(value: Array[Byte]): Array[Byte] = {
    value match {
      case null => nullMapValue
      case value => {
        val stored = new Array[Byte](value.length + 1)
        stored(0) = notNullMapValueHeader
        System.arraycopy(value, 0, stored, 1, value.length)
        stored
      }
    }
  }

  def getMapValueFromStored(value: Array[Byte]): Array[Byte] = {

    if (value(0) == nullMapValueHeader) {
      null
    } else if (value(0) == notNullMapValueHeader) {
      val returned = new Array[Byte](value.length - 1)
      System.arraycopy(value, 1, returned, 0, value.length - 1)
      returned
    } else {
      throw new StorageException("unknown header byte on map value:" + value(0))
    }
  }

  object IntSerializer {
    val bytesPerInt = java.lang.Integer.SIZE / java.lang.Byte.SIZE

    def toBytes(i: Int) = ByteBuffer.wrap(new Array[Byte](bytesPerInt)).putInt(i).array()

    def fromBytes(bytes: Array[Byte]) = ByteBuffer.wrap(bytes).getInt()

    def toString(obj: Int) = obj.toString

    def fromString(str: String) = str.toInt
  }

  object SortedSetSerializer {
    def toBytes(set: SortedSet[Array[Byte]]): Array[Byte] = {
      val length = set.foldLeft(0) {
        (total, bytes) => {
          total + bytes.length + IntSerializer.bytesPerInt
        }
      }
      val allBytes = new Array[Byte](length)
      val written = set.foldLeft(0) {
        (total, bytes) => {
          val sizeBytes = IntSerializer.toBytes(bytes.length)
          System.arraycopy(sizeBytes, 0, allBytes, total, sizeBytes.length)
          System.arraycopy(bytes, 0, allBytes, total + sizeBytes.length, bytes.length)
          total + sizeBytes.length + bytes.length
        }
      }
      require(length == written, "Bytes Written Did not equal Calculated Length, written %d, length %d".format(written, length))
      allBytes
    }

    def fromBytes(bytes: Array[Byte]): SortedSet[Array[Byte]] = {
      var set = new TreeSet[Array[Byte]]
      if (bytes.length > IntSerializer.bytesPerInt) {
        var pos = 0
        while (pos < bytes.length) {
          val lengthBytes = new Array[Byte](IntSerializer.bytesPerInt)
          System.arraycopy(bytes, pos, lengthBytes, 0, IntSerializer.bytesPerInt)
          pos += IntSerializer.bytesPerInt
          val length = IntSerializer.fromBytes(lengthBytes)
          val item = new Array[Byte](length)
          System.arraycopy(bytes, pos, item, 0, length)
          set = set + item
          pos += length
        }
      }
      set
    }

  }

}

private[akka] object KVStorageBackend {

  import CommonStorageBackend._

  /**
   * Concat the ownerlenght+owner+key+ of owner so owned data will be colocated
   * Store the length of owner as first byte to work around the rare case
   * where ownerbytes1 + keybytes1 == ownerbytes2 + keybytes2 but ownerbytes1 != ownerbytes2
   */

  def getKey(owner: String, key: Array[Byte]): Array[Byte] = {
    val ownerBytes: Array[Byte] = owner.getBytes("UTF-8")
    val ownerLenghtBytes: Array[Byte] = IntSerializer.toBytes(owner.length)
    val theKey = new Array[Byte](ownerLenghtBytes.length + ownerBytes.length + key.length)
    System.arraycopy(ownerLenghtBytes, 0, theKey, 0, ownerLenghtBytes.length)
    System.arraycopy(ownerBytes, 0, theKey, ownerLenghtBytes.length, ownerBytes.length)
    System.arraycopy(key, 0, theKey, ownerLenghtBytes.length + ownerBytes.length, key.length)
    theKey
  }

  def getIndexedKey(owner: String, index: Int): Array[Byte] = {
    getKey(owner, IntSerializer.toBytes(index))
  }

}

private[akka] trait CommonStorageBackend extends MapStorageBackend[Array[Byte], Array[Byte]] with VectorStorageBackend[Array[Byte]] with RefStorageBackend[Array[Byte]] with QueueStorageBackend[Array[Byte]] with Logging {

  import CommonStorageBackend._

  val vectorHeadIndex = -1
  val vectorTailIndex = -2
  val queueHeadIndex = -1
  val queueTailIndex = -2
  val zero = IntSerializer.toBytes(0)
  val refItem = "refItem".getBytes("UTF-8")

  implicit val ordering = ArrayOrdering


  def refAccess: CommonStorageBackendAccess

  def vectorAccess: CommonStorageBackendAccess

  def mapAccess: CommonStorageBackendAccess

  def queueAccess: CommonStorageBackendAccess


  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    val result: Array[Byte] = refAccess.get(name, refItem)
    Option(result)
  }

  def insertRefStorageFor(name: String, element: Array[Byte]) = {
    element match {
      case null => refAccess.delete(name, refItem)
      case _ => refAccess.put(name, refItem, element)
    }
  }


  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int): List[(Array[Byte], Array[Byte])] = {
    val allkeys: SortedSet[Array[Byte]] = getMapKeys(name)
    val range = allkeys.rangeImpl(start, finish).take(count)
    getKeyValues(name, range)
  }

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = {
    val keys = getMapKeys(name)
    getKeyValues(name, keys)
  }

  private def getKeyValues(name: String, keys: SortedSet[Array[Byte]]): List[(Array[Byte], Array[Byte])] = {
    val all: Map[Array[Byte], Array[Byte]] =
      mapAccess.getAll(name, keys)

    var returned = new TreeMap[Array[Byte], Array[Byte]]()(ordering)
    all.foreach{
      (entry) => {
        entry match {
          case (namePlusKey: Array[Byte], value: Array[Byte]) => {
            //need to fix here
            returned += mapAccess.decodeMapKey(name, unwrapMapKey(namePlusKey)) -> getMapValueFromStored(value)
          }
        }
      }
    }
    returned.toList
  }

  def getMapStorageSizeFor(name: String): Int = {
    val keys = getMapKeys(name)
    keys.size
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    val result: Array[Byte] = mapAccess.get(name, wrapMapKey(key))
    result match {
      case null => None
      case _ => Some(getMapValueFromStored(result))
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]) = {
    val wrapped = wrapMapKey(key)
    var keys = getMapKeys(name)
    keys -= wrapped
    putMapKeys(name, keys)
    mapAccess.delete(name, wrapped)
  }

  def removeMapStorageFor(name: String) = {
    val keys = getMapKeys(name)
    keys.foreach{
      key =>
        mapAccess.delete(name, key)
    }
    mapAccess.delete(name, mapKeysIndex)
  }

  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) = {
    val wrapped = wrapMapKey(key)
    mapAccess.put(name, wrapped, getStoredMapValue(value))
    var keys = getMapKeys(name)
    keys += wrapped
    putMapKeys(name, keys)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) = {
    val toInsert = entries.map{
      kv => kv match {
        case (key, value) => (wrapMapKey(key) -> getStoredMapValue(value))
      }
    }
    mapAccess.putAll(name, toInsert)
    val newKeys = toInsert.map{
      case (key, value) => {
        key
      }
    }
    var keys = getMapKeys(name)
    keys ++= newKeys
    putMapKeys(name, keys)
  }

  def putMapKeys(name: String, keys: SortedSet[Array[Byte]]) = {
    mapAccess.put(name, mapKeysIndex, SortedSetSerializer.toBytes(keys))
  }

  def getMapKeys(name: String): SortedSet[Array[Byte]] = {
    SortedSetSerializer.fromBytes(mapAccess.get(name, mapKeysIndex, Array.empty[Byte]))
  }

  def getVectorStorageSizeFor(name: String): Int = {
    getVectorMetadata(name).size
  }

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    val mdata = getVectorMetadata(name)

    val st = start.getOrElse(0)
    var cnt =
      if (finish.isDefined) {
        val f = finish.get
        if (f >= st) (f - st) else count
      } else {
        count
      }
    if (cnt > (mdata.size - st)) {
      cnt = mdata.size - st
    }

    val indexes = mdata.getRangeIndexes(st, count)
    val result = vectorAccess.getAllIndexed(name, indexes)
    indexes.map(result.get(_).get).toList

  }

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = {
    val mdata = getVectorMetadata(name)
    if (mdata.size > 0 && index < mdata.size) {
      vectorAccess.getIndexed(name, mdata.getRangeIndexes(index, 1)(0))
    } else {
      throw new StorageException("In Vector:" + name + " No such Index:" + index)
    }
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {
    val mdata = getVectorMetadata(name)
    if (mdata.size > 0 && index < mdata.size) {
      elem match {
        case null => vectorAccess.deleteIndexed(name, mdata.getRangeIndexes(index, 1)(0))
        case _ => vectorAccess.putIndexed(name, mdata.getRangeIndexes(index, 1)(0), elem)
      }
    } else {
      throw new StorageException("In Vector:" + name + " No such Index:" + index)
    }
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = {
    var mdata = getVectorMetadata(name)
    var deletes: List[Int] = Nil
    var puts: List[(Int, Array[Byte])] = Nil
    elements.foreach{
      element => {
        if (mdata.canInsert) {
          element match {
            case null => deletes = mdata.head :: deletes
            case _ => puts = (mdata.head -> element) :: puts
          }
          mdata = mdata.copy(head = mdata.nextInsert)
        } else {
          throw new IllegalStateException("The vector dosent have enough capacity to insert these entries")
        }
      }
    }

    vectorAccess.deleteAllIndexed(name, deletes)
    vectorAccess.putAllIndexed(name, puts)
    vectorAccess.putIndexed(name, vectorHeadIndex, IntSerializer.toBytes(mdata.head))

  }

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    val mdata = getVectorMetadata(name)
    if (mdata.canInsert) {
      element match {
        case null => vectorAccess.deleteIndexed(name, mdata.head)
        case _ => vectorAccess.putIndexed(name, mdata.head, element)
      }
      vectorAccess.putIndexed(name, vectorHeadIndex, IntSerializer.toBytes(mdata.nextInsert))
    } else {
      throw new IllegalStateException("The vector %s is full".format(name))
    }

  }


  override def removeVectorStorageEntryFor(name: String) = {
    val mdata = getVectorMetadata(name)
    if (mdata.canRemove) {
      vectorAccess.putIndexed(name, vectorTailIndex, IntSerializer.toBytes(mdata.nextRemove))
      try
      {
        vectorAccess.deleteIndexed(name, mdata.tail)
      } catch {
        case e: Exception => log.warn("Exception while trying to clean up a popped element from the vector, this is acceptable")
      }

    } else {
      //blow up or not?
    }
  }

  def getVectorMetadata(name: String): VectorMetadata = {
    val result = vectorAccess.getAllIndexed(name, List(vectorHeadIndex, vectorTailIndex))
    val head = result.getOrElse(vectorHeadIndex, zero)
    val tail = result.getOrElse(vectorTailIndex, zero)
    val mdata = VectorMetadata(IntSerializer.fromBytes(head), IntSerializer.fromBytes(tail))
    mdata
  }

  def getOrDefaultToZero(map: Map[Array[Byte], Array[Byte]], key: Array[Byte]): Int = {
    map.get(key) match {
      case Some(value) => IntSerializer.fromBytes(value)
      case None => 0
    }
  }


  def remove(name: String): Boolean = {
    val mdata = getQueueMetadata(name)
    mdata.getActiveIndexes foreach {
      index =>
        queueAccess.deleteIndexed(name, index)
    }
    queueAccess.deleteIndexed(name, queueHeadIndex)
    queueAccess.deleteIndexed(name, queueTailIndex)
    true
  }

  def peek(name: String, start: Int, count: Int): List[Array[Byte]] = {
    val mdata = getQueueMetadata(name)
    val indexes = mdata.getPeekIndexes(start, count)
    val result = queueAccess.getAllIndexed(name, indexes)
    indexes.map(result.get(_).get).toList
  }

  def size(name: String): Int = {
    getQueueMetadata(name).size
  }

  def dequeue(name: String): Option[Array[Byte]] = {
    val mdata = getQueueMetadata(name)
    if (mdata.canDequeue) {
      try
      {
        val dequeued = queueAccess.getIndexed(name, mdata.head)
        queueAccess.putIndexed(name, queueHeadIndex, IntSerializer.toBytes(mdata.nextDequeue))
        Some(dequeued)
      } finally {
        try
        {
          queueAccess.deleteIndexed(name, mdata.head)
        } catch {
          //a failure to delete is ok, just leaves a K-V in Voldemort that will be overwritten if the queue ever wraps around
          case e: Exception => log.warn(e, "caught an exception while deleting a dequeued element, however this will not cause any inconsistency in the queue")
        }
      }
    } else {
      None
    }
  }

  def enqueue(name: String, item: Array[Byte]): Option[Int] = {
    val mdata = getQueueMetadata(name)
    if (mdata.canEnqueue) {
      item match {
        case null => queueAccess.deleteIndexed(name, mdata.tail)
        case _ => queueAccess.putIndexed(name, mdata.tail, item)
      }
      queueAccess.putIndexed(name, queueTailIndex, IntSerializer.toBytes(mdata.nextEnqueue))
      Some(mdata.size + 1)
    } else {
      None
    }
  }

  def getQueueMetadata(name: String): QueueMetadata = {
    val result = queueAccess.getAllIndexed(name, List(vectorHeadIndex, vectorTailIndex))
    val head = result.get(vectorHeadIndex).getOrElse(zero)
    val tail = result.get(vectorTailIndex).getOrElse(zero)
    QueueMetadata(IntSerializer.fromBytes(head), IntSerializer.fromBytes(tail))
  }


  //wrapper for null


  case class QueueMetadata(head: Int, tail: Int) {
    //queue is an sequence with indexes from 0 to Int.MAX_VALUE
    //wraps around when one pointer gets to max value
    //head has an element in it.
    //tail is the next slot to write to.

    def size = {
      if (tail >= head) {
        tail - head
      } else {
        //queue has wrapped
        (Integer.MAX_VALUE - head) + (tail + 1)
      }
    }

    def canEnqueue = {
      //the -1 stops the tail from catching the head on a wrap around
      size < Integer.MAX_VALUE - 1
    }

    def canDequeue = {
      size > 0
    }

    def getActiveIndexes(): IndexedSeq[Int] = {
      if (tail >= head) {
        Range(head, tail)
      } else {
        //queue has wrapped
        val headRange = Range.inclusive(head, Integer.MAX_VALUE)
        (if (tail > 0) {
          headRange ++ Range(0, tail)
        } else {
          headRange
        })
      }
    }

    def getPeekIndexes(start: Int, count: Int): IndexedSeq[Int] = {
      val indexes = getActiveIndexes
      if (indexes.size < start) {
        IndexedSeq.empty[Int]
      } else {
        indexes.drop(start).take(count)
      }
    }

    def nextEnqueue = {
      tail match {
        case Integer.MAX_VALUE => 0
        case _ => tail + 1
      }
    }

    def nextDequeue = {
      head match {
        case Integer.MAX_VALUE => 0
        case _ => head + 1
      }
    }
  }

  case class VectorMetadata(head: Int, tail: Int) {
    def size = {
      if (head >= tail) {
        head - tail
      } else {
        //queue has wrapped
        (Integer.MAX_VALUE - tail) + (head + 1)
      }
    }

    def canInsert = {
      //the -1 stops the tail from catching the head on a wrap around
      size < Integer.MAX_VALUE - 1
    }

    def canRemove = {
      size > 0
    }

    def getActiveIndexes(): IndexedSeq[Int] = {
      if (head >= tail) {
        Range(tail, head)
      } else {
        //queue has wrapped
        val headRange = Range.inclusive(tail, Integer.MAX_VALUE)
        (if (head > 0) {
          headRange ++ Range(0, head)
        } else {
          headRange
        })
      }
    }

    def getRangeIndexes(start: Int, count: Int): IndexedSeq[Int] = {
      val indexes = getActiveIndexes.reverse
      if (indexes.size < start) {
        IndexedSeq.empty[Int]
      } else {
        indexes.drop(start).take(count)
      }
    }

    def nextInsert = {
      head match {
        case Integer.MAX_VALUE => 0
        case _ => head + 1
      }
    }

    def nextRemove = {
      tail match {
        case Integer.MAX_VALUE => 0
        case _ => tail + 1
      }
    }
  }


}
