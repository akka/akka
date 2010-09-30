/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.voldemort

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.config.Config.config

import voldemort.client._
import java.lang.String
import voldemort.utils.ByteUtils
import voldemort.versioning.Versioned
import collection.JavaConversions
import java.nio.ByteBuffer
import collection.Map
import collection.immutable.{IndexedSeq, SortedSet, TreeSet, HashMap}
import collection.mutable.{Set, HashSet, ArrayBuffer}
import java.util.{Properties, Map => JMap}

/*
  RequiredReads + RequiredWrites should be > ReplicationFactor for all Voldemort Stores
  In this case all VoldemortBackend operations can be retried until successful, and data should remain consistent
 */

private[akka] object VoldemortStorageBackend extends
MapStorageBackend[Array[Byte], Array[Byte]] with
        VectorStorageBackend[Array[Byte]] with
        RefStorageBackend[Array[Byte]] with
        QueueStorageBackend[Array[Byte]] with
        Logging {
  val bootstrapUrlsProp = "bootstrap_urls"
  val clientConfig = config.getConfigMap("akka.storage.voldemort.client") match {
    case Some(configMap) => getClientConfig(configMap.asMap)
    case None => getClientConfig(new HashMap[String, String] + (bootstrapUrlsProp -> "tcp://localhost:6666"))
  }
  val refStore = config.getString("akka.storage.voldemort.store.ref", "Refs")
  val mapStore = config.getString("akka.storage.voldemort.store.map", "Maps")
  val vectorStore = config.getString("akka.storage.voldemort.store.vector", "Vectors")
  val queueStore = config.getString("akka.storage.voldemort.store.queue", "Queues")

  var storeClientFactory: StoreClientFactory = null
  var refClient: StoreClient[String, Array[Byte]] = null
  var mapClient: StoreClient[Array[Byte], Array[Byte]] = null
  var vectorClient: StoreClient[Array[Byte], Array[Byte]] = null
  var queueClient: StoreClient[Array[Byte], Array[Byte]] = null
  initStoreClients

  val underscoreBytesUTF8 = "_".getBytes("UTF-8")
  val mapKeysIndex = getIndexedBytes(-1)
  val vectorSizeIndex = getIndexedBytes(-1)
  val queueHeadIndex = getIndexedBytes(-1)
  val queueTailIndex = getIndexedBytes(-2)


  implicit val byteOrder = new Ordering[Array[Byte]] {
    override def compare(x: Array[Byte], y: Array[Byte]) = ByteUtils.compare(x, y)
  }


  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    val result: Array[Byte] = refClient.getValue(name)
    result match {
      case null => None
      case _ => Some(result)
    }
  }

  def insertRefStorageFor(name: String, element: Array[Byte]) = {
    refClient.put(name, element)
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
    val all: JMap[Array[Byte], Versioned[Array[Byte]]] =
    mapClient.getAll(JavaConversions.asIterable(keys.map {
      mapKey => getKey(name, mapKey)
    }))

    val buf = new ArrayBuffer[(Array[Byte], Array[Byte])](all.size)
    JavaConversions.asMap(all).foreach {
      (entry) => {
        entry match {
          case (key: Array[Byte], versioned: Versioned[Array[Byte]]) => {
            buf += key -> versioned.getValue
          }
        }
      }
    }
    buf.toList
  }

  def getMapStorageSizeFor(name: String): Int = {
    val keys = getMapKeys(name)
    keys.size
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    val result: Array[Byte] = mapClient.getValue(getKey(name, key))
    result match {
      case null => None
      case _ => Some(result)
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]) = {
    var keys = getMapKeys(name)
    keys -= key
    putMapKeys(name, keys)
    mapClient.delete(getKey(name, key))
  }


  def removeMapStorageFor(name: String) = {
    val keys = getMapKeys(name)
    keys.foreach {
      key =>
        mapClient.delete(getKey(name, key))
    }
    mapClient.delete(getKey(name, mapKeysIndex))
  }

  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) = {
    mapClient.put(getKey(name, key), value)
    var keys = getMapKeys(name)
    keys += key
    putMapKeys(name, keys)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) = {
    val newKeys = entries.map {
      case (key, value) => {
        mapClient.put(getKey(name, key), value)
        key
      }
    }
    var keys = getMapKeys(name)
    keys ++= newKeys
    putMapKeys(name, keys)
  }

  def putMapKeys(name: String, keys: SortedSet[Array[Byte]]) = {
    mapClient.put(getKey(name, mapKeysIndex), SortedSetSerializer.toBytes(keys))
  }

  def getMapKeys(name: String): SortedSet[Array[Byte]] = {
    SortedSetSerializer.fromBytes(mapClient.getValue(getKey(name, mapKeysIndex), Array.empty[Byte]))
  }


  def getVectorStorageSizeFor(name: String): Int = {
    IntSerializer.fromBytes(vectorClient.getValue(getKey(name, vectorSizeIndex), IntSerializer.toBytes(0)))
  }


  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    val size = getVectorStorageSizeFor(name)
    val st = start.getOrElse(0)
    val cnt =
    if (finish.isDefined) {
      val f = finish.get
      if (f >= st) (f - st) else count
    } else {
      count
    }
    val seq: IndexedSeq[Array[Byte]] = (st until st + cnt).map {
      index => getIndexedKey(name, index)
    }

    val all: JMap[Array[Byte], Versioned[Array[Byte]]] = vectorClient.getAll(JavaConversions.asIterable(seq))

    var storage = new ArrayBuffer[Array[Byte]](seq.size)
    storage = storage.padTo(seq.size, Array.empty[Byte])
    var idx = 0;
    seq.foreach {
      key => {
        if (all.containsKey(key)) {
          storage.update(idx, all.get(key).getValue)
        }
        idx += 1
      }
    }

    storage.toList
  }


  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = {
    vectorClient.getValue(getIndexedKey(name, index), Array.empty[Byte])
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {
    val size = getVectorStorageSizeFor(name)
    vectorClient.put(getIndexedKey(name, index), elem)
    if (size < index + 1) {
      vectorClient.put(getKey(name, vectorSizeIndex), IntSerializer.toBytes(index + 1))
    }
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = {
    var size = getVectorStorageSizeFor(name)
    elements.foreach {
      element =>
        vectorClient.put(getIndexedKey(name, size), element)
        size += 1
    }
    vectorClient.put(getKey(name, vectorSizeIndex), IntSerializer.toBytes(size))
  }

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    insertVectorStorageEntriesFor(name, List(element))
  }


  def remove(name: String): Boolean = {
    val mdata = getQueueMetadata(name)
    mdata.getActiveIndexes foreach {
      index =>
        queueClient.delete(getIndexedKey(name, index))
    }
    queueClient.delete(getKey(name, queueHeadIndex))
    queueClient.delete(getKey(name, queueTailIndex))
  }

  def peek(name: String, start: Int, count: Int): List[Array[Byte]] = {
    val mdata = getQueueMetadata(name)
    val ret = mdata.getPeekIndexes(start, count).toList map {
      index: Int => {
        log.debug("peeking:" + index)
        queueClient.getValue(getIndexedKey(name, index))
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
        val dequeued = queueClient.getValue(key)
        queueClient.put(getKey(name, queueHeadIndex), IntSerializer.toBytes(mdata.nextDequeue))
        Some(dequeued)
      }
      finally {
        try {
          queueClient.delete(key)
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
      queueClient.put(key, item)
      queueClient.put(getKey(name, queueTailIndex), IntSerializer.toBytes(mdata.nextEnqueue))
      Some(mdata.size + 1)
    } else {
      None
    }
  }


  def getQueueMetadata(name: String): QueueMetadata = {
    val keys = List(getKey(name, queueHeadIndex), getKey(name, queueTailIndex))
    val qdata = JavaConversions.asMap(queueClient.getAll(JavaConversions.asIterable(keys)))
    val values = keys.map {
      qdata.get(_) match {
        case Some(versioned) => IntSerializer.fromBytes(versioned.getValue)
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


  def getClientConfig(configMap: Map[String, String]): Properties = {
    val properites = new Properties
    configMap.foreach {
      keyval => keyval match {
        case (key, value) => properites.setProperty(key.asInstanceOf[java.lang.String], value.asInstanceOf[java.lang.String])
      }
    }
    properites
  }

  def initStoreClients() = {
    if (storeClientFactory ne null) {
      storeClientFactory.close
    }

    storeClientFactory = {
      if (clientConfig.getProperty(bootstrapUrlsProp, "none").startsWith("tcp")) {
        new SocketStoreClientFactory(new ClientConfig(clientConfig))
      } else if (clientConfig.getProperty(bootstrapUrlsProp, "none").startsWith("http")) {
        new HttpStoreClientFactory(new ClientConfig(clientConfig))
      } else {
        throw new IllegalArgumentException("Unknown boostrapUrl syntax" + clientConfig.getProperty(bootstrapUrlsProp, "No Bootstrap URLs defined"))
      }
    }
    refClient = storeClientFactory.getStoreClient(refStore)
    mapClient = storeClientFactory.getStoreClient(mapStore)
    vectorClient = storeClientFactory.getStoreClient(vectorStore)
    queueClient = storeClientFactory.getStoreClient(queueStore)
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