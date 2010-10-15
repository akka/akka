/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.riak

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.config.Config.config

import java.lang.String
import collection.JavaConversions
import java.nio.ByteBuffer
import collection.Map
import collection.mutable.ArrayBuffer
import java.util.{Properties, Map => JMap}
import se.scalablesolutions.akka.persistence.common.PersistentMapBinary.COrdering._
import collection.immutable._
import com.google.protobuf.ByteString
import com.google.protobuf.ByteString._
import com.trifork.riak.{RequestMeta, RiakObject, RiakClient}


private[akka] object RiakStorageBackend extends
MapStorageBackend[Array[Byte], Array[Byte]] with
        VectorStorageBackend[Array[Byte]] with
        RefStorageBackend[Array[Byte]] with
        QueueStorageBackend[Array[Byte]] with
        Logging {
  val refBucket = config.getString("akka.storage.riak.bucket.ref", "Refs")
  val mapBucket = config.getString("akka.storage.riak.bucket.map", "Maps")
  val vectorBucket = config.getString("akka.storage.riak.bucket.vector", "Vectors")
  val queueBucket = config.getString("akka.storage.riak.bucket.queue", "Queues")
  val clientHost = config.getString("akka.storage.riak.client.host", "localhost")
  val clientPort = config.getInt("akka.storage.riak.client.port", 8087)
  var riakClient: RiakClient = new RiakClient(clientHost, clientPort);

  val nullMapValueHeader = 0x00.byteValue
  val nullMapValue: Array[Byte] = Array(nullMapValueHeader)
  val notNullMapValueHeader: Byte = 0xff.byteValue
  val underscoreBytesUTF8 = "_".getBytes("UTF-8")
  val mapKeysIndex = getIndexedBytes(-1)
  val vectorSizeIndex = getIndexedBytes(-1)
  val queueHeadIndex = getIndexedBytes(-1)
  val queueTailIndex = getIndexedBytes(-2)
  //explicit implicit :)
  implicit val ordering = ArrayOrdering
  import RiakAccess._

  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    val result: Array[Byte] = RefClient.getValue(name)
    Option(result)
  }

  def insertRefStorageFor(name: String, element: Array[Byte]) = {
    element match {
      case null => RefClient.delete(name)
      case _ => RefClient.put(name, element)
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
    MapClient.getAll(keys.map {
      mapKey => getKey(name, mapKey)
    })

    var returned = new TreeMap[Array[Byte], Array[Byte]]()(ordering)
    all.foreach {
      (entry) => {
        entry match {
          case (namePlusKey: Array[Byte], value: Array[Byte]) => {
            returned += getMapKeyFromKey(name, namePlusKey) -> getMapValueFromStored(value)
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
    val result: Array[Byte] = MapClient.getValue(getKey(name, key))
    result match {
      case null => None
      case _ => Some(getMapValueFromStored(result))
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]) = {
    var keys = getMapKeys(name)
    keys -= key
    putMapKeys(name, keys)
    MapClient.delete(getKey(name, key))
  }


  def removeMapStorageFor(name: String) = {
    val keys = getMapKeys(name)
    keys.foreach {
      key =>
        MapClient.delete(getKey(name, key))
        log.debug("deleted key %s for %s", key, name)
    }
    MapClient.delete(getKey(name, mapKeysIndex))
  }

  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) = {
    MapClient.put(getKey(name, key), getStoredMapValue(value))
    var keys = getMapKeys(name)
    keys += key
    putMapKeys(name, keys)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) = {
    val newKeys = entries.map {
      case (key, value) => {
        MapClient.put(getKey(name, key), getStoredMapValue(value))
        key
      }
    }
    var keys = getMapKeys(name)
    keys ++= newKeys
    putMapKeys(name, keys)
  }

  def putMapKeys(name: String, keys: SortedSet[Array[Byte]]) = {
    MapClient.put(getKey(name, mapKeysIndex), SortedSetSerializer.toBytes(keys))
  }

  def getMapKeys(name: String): SortedSet[Array[Byte]] = {
    SortedSetSerializer.fromBytes(MapClient.getValue(getKey(name, mapKeysIndex), Array.empty[Byte]))
  }


  def getVectorStorageSizeFor(name: String): Int = {
    IntSerializer.fromBytes(VectorClient.getValue(getKey(name, vectorSizeIndex), IntSerializer.toBytes(0)))
  }


  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    val size = getVectorStorageSizeFor(name)
    val st = start.getOrElse(0)
    var cnt =
    if (finish.isDefined) {
      val f = finish.get
      if (f >= st) (f - st) else count
    } else {
      count
    }
    if (cnt > (size - st)) {
      cnt = size - st
    }


    val seq: IndexedSeq[Array[Byte]] = (st until st + cnt).map {
      index => getIndexedKey(name, (size - 1) - index)
    } //read backwards

    val all: Map[Array[Byte], Array[Byte]] = VectorClient.getAll(seq)

    var storage = new ArrayBuffer[Array[Byte]](seq.size)
    storage = storage.padTo(seq.size, Array.empty[Byte])
    var idx = 0;
    seq.foreach {
      key => {
        if (all.isDefinedAt(key)) {
          storage.update(idx, all.get(key).get)
        }
        idx += 1
      }
    }

    storage.toList
  }


  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = {
    val size = getVectorStorageSizeFor(name)
    if (size > 0 && index < size) {
      VectorClient.getValue(getIndexedKey(name, /*read backwards*/ (size - 1) - index))
    } else {
      throw new StorageException("In Vector:" + name + " No such Index:" + index)
    }
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {
    val size = getVectorStorageSizeFor(name)
    if (size > 0 && index < size) {
      elem match {
        case null => VectorClient.delete(getIndexedKey(name, /*read backwards*/ (size - 1) - index))
        case _ => VectorClient.put(getIndexedKey(name, /*read backwards*/ (size - 1) - index), elem)
      }
    } else {
      throw new StorageException("In Vector:" + name + " No such Index:" + index)
    }
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = {
    var size = getVectorStorageSizeFor(name)
    elements.foreach {
      element =>
        if (element != null) {
          VectorClient.put(getIndexedKey(name, size), element)
        }
        size += 1
    }
    VectorClient.put(getKey(name, vectorSizeIndex), IntSerializer.toBytes(size))
  }

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    insertVectorStorageEntriesFor(name, List(element))
  }


  def remove(name: String): Boolean = {
    val mdata = getQueueMetadata(name)
    mdata.getActiveIndexes foreach {
      index =>
        QueueClient.delete(getIndexedKey(name, index))
    }
    QueueClient.delete(getKey(name, queueHeadIndex))
    QueueClient.delete(getKey(name, queueTailIndex))
    true
  }

  def peek(name: String, start: Int, count: Int): List[Array[Byte]] = {
    val mdata = getQueueMetadata(name)
    val ret = mdata.getPeekIndexes(start, count).toList map {
      index: Int => {
        log.debug("peeking:" + index)
        QueueClient.getValue(getIndexedKey(name, index))
      }
    }
    ret
  }

  def size(name: String): Int = {
    getQueueMetadata(name).size
  }

  def dequeue(name: String): Option[Array[Byte]] = {
    val mdata = getQueueMetadata(name)
    if (mdata.canDequeue) {
      val key = getIndexedKey(name, mdata.head)
      try {
        val dequeued = QueueClient.getValue(key)
        QueueClient.put(getKey(name, queueHeadIndex), IntSerializer.toBytes(mdata.nextDequeue))
        Some(dequeued)
      }
      finally {
        try {
          QueueClient.delete(key)
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
      val key = getIndexedKey(name, mdata.tail)
      item match {
        case null => QueueClient.delete(key)
        case _ => QueueClient.put(key, item)
      }
      QueueClient.put(getKey(name, queueTailIndex), IntSerializer.toBytes(mdata.nextEnqueue))
      Some(mdata.size + 1)
    } else {
      None
    }
  }


  def getQueueMetadata(name: String): QueueMetadata = {
    val keys = List(getKey(name, queueHeadIndex), getKey(name, queueTailIndex))
    val qdata = QueueClient.getAll(keys)
    val values = keys.map {
      qdata.get(_) match {
        case Some(value) => IntSerializer.fromBytes(value)
        case None => 0
      }
    }
    QueueMetadata(values.head, values.tail.head)
  }

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

  def getIndexedBytes(index: Int): Array[Byte] = {
    val indexbytes = IntSerializer.toBytes(index)
    val theIndexKey = new Array[Byte](underscoreBytesUTF8.length + indexbytes.length)
    System.arraycopy(underscoreBytesUTF8, 0, theIndexKey, 0, underscoreBytesUTF8.length)
    System.arraycopy(indexbytes, 0, theIndexKey, underscoreBytesUTF8.length, indexbytes.length)
    theIndexKey
  }

  def getIndexedKey(owner: String, index: Int): Array[Byte] = {
    getKey(owner, getIndexedBytes(index))
  }

  def getIndexFromVectorValueKey(owner: String, key: Array[Byte]): Int = {
    val indexBytes = new Array[Byte](IntSerializer.bytesPerInt)
    System.arraycopy(key, key.length - IntSerializer.bytesPerInt, indexBytes, 0, IntSerializer.bytesPerInt)
    IntSerializer.fromBytes(indexBytes)
  }

  def getMapKeyFromKey(owner: String, key: Array[Byte]): Array[Byte] = {
    val mapKeyLength = key.length - IntSerializer.bytesPerInt - owner.getBytes("UTF-8").length
    val mapkey = new Array[Byte](mapKeyLength)
    System.arraycopy(key, key.length - mapKeyLength, mapkey, 0, mapKeyLength)
    mapkey
  }

  //wrapper for null
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


  def getClientConfig(configMap: Map[String, String]): Properties = {
    val properites = new Properties
    configMap.foreach {
      keyval => keyval match {
        case (key, value) => properites.setProperty(key.asInstanceOf[java.lang.String], value.asInstanceOf[java.lang.String])
      }
    }
    properites
  }




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

    def canDequeue = {size > 0}

    def getActiveIndexes(): IndexedSeq[Int] = {
      if (tail >= head) {
        Range(head, tail)
      } else {
        //queue has wrapped
        val headRange = Range.inclusive(head, Integer.MAX_VALUE)
        (if (tail > 0) {headRange ++ Range(0, tail)} else {headRange})
      }
    }

    def getPeekIndexes(start: Int, count: Int): IndexedSeq[Int] = {
      val indexes = getActiveIndexes
      if (indexes.size < start)
        {IndexedSeq.empty[Int]} else
        {indexes.drop(start).take(count)}
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



  object RiakAccess {
    implicit def byteArrayToByteString(ary: Array[Byte]): ByteString = {
      ByteString.copyFrom(ary)
    }

    implicit def byteStringToByteArray(bs: ByteString): Array[Byte] = {
      bs.toByteArray
    }

    implicit def stringToByteString(bucket: String): ByteString = {
      ByteString.copyFromUtf8(bucket)
    }

    implicit def stringToByteArray(st: String): Array[Byte] = {
      st.getBytes("UTF-8")
    }
  }

  trait RiakAccess {
    def bucket: String
    //http://www.mail-archive.com/riak-users@lists.basho.com/msg01013.html
    val quorum: Int = 0xfffffffd
    val one: Int = 0xfffffffe
    val all: Int = 0xfffffffc
    val default: Int = 0xfffffffb

    def put(key: Array[Byte], value: Array[Byte]) = {
      val objs: Array[RiakObject] = riakClient.fetch(bucket, key, quorum)
      objs.size match {
        case 0 => riakClient.store(new RiakObject(bucket, key, value), new RequestMeta().w(quorum).dw(quorum))
        case _ => riakClient.store(new RiakObject(objs(0).getVclock, bucket, key, value),new RequestMeta().w(quorum).dw(quorum))
      }
    }

    def getValue(key: Array[Byte]): Array[Byte] = {
      val objs = riakClient.fetch(bucket, key, quorum)
      objs.size match {
        case 0 => null;
        case _ => objs(0).getValue.isEmpty match {
          case true => null
          case false => objs(0).getValue
        }
      }
    }

    def getValue(key: Array[Byte], default: Array[Byte]): Array[Byte] = {
      Option(getValue(key)) match {
        case Some(value) => value
        case None => default
      }
    }

    def getAll(keys: Traversable[Array[Byte]]): Map[Array[Byte], Array[Byte]] = {
      var result = new HashMap[Array[Byte], Array[Byte]]
      keys.foreach {
        key =>
          val value = getValue(key)
          Option(value) match {
            case Some(value) => result += key -> value
            case None => ()
          }
      }
      result
    }

    def delete(key: Array[Byte]) = {
      riakClient.delete(bucket, key, quorum)
    }

    def drop() {
      val keys = riakClient.listKeys(bucket)
      JavaConversions.asIterable(keys) foreach {
        delete(_)
      }
      keys.close
    }
  }

  object RefClient extends RiakAccess {
    def bucket: String = refBucket
  }

  object MapClient extends RiakAccess {
    def bucket = mapBucket
  }

  object VectorClient extends RiakAccess {
    def bucket = vectorBucket
  }

  object QueueClient extends RiakAccess {
    def bucket = queueBucket
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
      import se.scalablesolutions.akka.persistence.common.PersistentMapBinary.COrdering._

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