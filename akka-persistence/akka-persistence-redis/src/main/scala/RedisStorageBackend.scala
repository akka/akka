/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.Config.config

import com.redis._

trait Encoder {
  def encode(bytes: Array[Byte]): Array[Byte]
  def decode(bytes: Array[Byte]): Array[Byte]
}

trait CommonsCodecBase64 {
  val base64 = new org.apache.commons.codec.binary.Base64
  
  def encode(bytes: Array[Byte]): Array[Byte] = base64.encode(bytes)
  def decode(bytes: Array[Byte]): Array[Byte] = base64.decode(bytes)
}

object Base64Encoder extends Encoder with CommonsCodecBase64
import Base64Encoder._

/**
 * A module for supporting Redis based persistence.
 * <p/>
 * The module offers functionality for:
 * <li>Persistent Maps</li>
 * <li>Persistent Vectors</li>
 * <li>Persistent Refs</li>
 * <p/>
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */
private [akka] object RedisStorageBackend extends 
  MapStorageBackend[Array[Byte], Array[Byte]] with 
  VectorStorageBackend[Array[Byte]] with 
  RefStorageBackend[Array[Byte]] with
  QueueStorageBackend[Array[Byte]] with
  SortedSetStorageBackend[Array[Byte]] with
  Logging {
    
  val REDIS_SERVER_HOSTNAME = config.getString("akka.storage.redis.hostname", "127.0.0.1")
  val REDIS_SERVER_PORT = config.getInt("akka.storage.redis.port", 6379)

  val db = new Redis(REDIS_SERVER_HOSTNAME, REDIS_SERVER_PORT)
  
  /**
   * Map storage in Redis.
   * <p/>
   * Maps are stored as key/value pairs in redis. <i>Redis keys cannot contain spaces</i>. But with
   * our use case, the keys will be specified by the user. Hence we need to encode the key
   * ourselves before sending to Redis. We use base64 encoding.
   * <p/>
   * Also since we are storing the key/value in the global namespace, we need to construct the
   * key suitably so as to avoid namespace clash. The following strategy is used:
   * 
   * Unique identifier for the map = T1 (say)
   * <pre>
   * Map(
   *   "debasish.address" -> "kolkata, India",
   *   "debasish.company" -> "anshinsoft",
   *   "debasish.programming_language" -> "scala",
   * )</pre>
   * will be stored as the following key-value pair in Redis:
   * 
   * <i>
   * base64(T1):base64("debasish.address") -> "kolkata, India"
   * base64(T1):base64("debasish.company") -> "anshinsoft"
   * base64(T1):base64("debasish.programming_language") -> "scala"
   * </i>
   */
  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) {
    insertMapStorageEntriesFor(name, List((key, value)))
  }

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[Array[Byte], Array[Byte]]]) {
    mset(entries.map(e =>
          (makeRedisKey(name, e._1), new String(e._2))))
  }
  
  /**
   * Make a redis key from an Akka Map key.
   * <p/>
   * The key is made as follows:
   * <li>redis key is composed of 2 parts: the transaction id and the map key separated by :</li>
   * <li>: is chosen since it cannot appear in base64 encoding charset</li>
   * <li>both parts of the key need to be based64 encoded since there can be spaces within each of them</li>
   */
  private [this] def makeRedisKey(name: String, key: Array[Byte]): String = {
    "%s:%s".format(new String(encode(name.getBytes)), new String(encode(key)))
  }
  
  private [this] def makeKeyFromRedisKey(redisKey: String) = {
    val nk = redisKey.split(':').map{e: String => decode(e.getBytes)}
    (nk(0), nk(1))
  }
  
  private [this] def mset(entries: List[(String, String)]) {
    entries.foreach {e: (String, String) =>
      db.set(e._1, e._2)
    }
  }

  def removeMapStorageFor(name: String): Unit = {
    db.keys("%s:*".format(encode(name.getBytes))) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " not present")
      case Some(keys) =>
        keys.foreach(db.delete(_))
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = {
    db.delete(makeRedisKey(name, key))
  }
    
  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = 
    db.get(makeRedisKey(name, key)) match {
      case None =>
        throw new java.util.NoSuchElementException(new String(key) + " not present")
      case Some(s) => Some(s.getBytes)
    }

  def getMapStorageSizeFor(name: String): Int = {
    db.keys("%s:*".format(new String(encode(name.getBytes)))) match {
      case None => 0
      case Some(keys) => 
        keys.length
    }
  }

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = {
    db.keys("%s:*".format(new String(encode(name.getBytes)))) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " not present")
      case Some(keys) =>
        keys.map(key => (makeKeyFromRedisKey(key)._2, db.get(key).get.getBytes)).toList
    }
  }

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]],
                            finish: Option[Array[Byte]],
                            count: Int): List[(Array[Byte], Array[Byte])] = {

    import scala.collection.immutable.TreeMap
    val wholeSorted = 
      TreeMap(getMapStorageFor(name).map(e => (new String(e._1), e._2)): _*)

    if (wholeSorted isEmpty) List()

    val startKey =
      start match {
        case Some(bytes) => Some(new String(bytes))
        case None => None
      }

    val endKey =
      finish match {
        case Some(bytes) => Some(new String(bytes))
        case None => None
      }

    ((startKey, endKey, count): @unchecked) match {
      case ((Some(s), Some(e), _)) =>
        wholeSorted.range(s, e)
                   .toList
                   .map(e => (e._1.getBytes, e._2))
                   .toList
      case ((Some(s), None, c)) if c > 0 =>
        wholeSorted.from(s)
                   .elements
                   .take(count)
                   .map(e => (e._1.getBytes, e._2))
                   .toList
      case ((Some(s), None, _)) =>
        wholeSorted.from(s)
                   .toList
                   .map(e => (e._1.getBytes, e._2))
                   .toList
      case ((None, Some(e), _)) =>
        wholeSorted.until(e)
                   .toList
                   .map(e => (e._1.getBytes, e._2))
                   .toList
    }
  }
  
  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) {
    db.pushHead(new String(encode(name.getBytes)), new String(element))
  }
  
  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) {
    elements.foreach(insertVectorStorageEntryFor(name, _))
  }
  
  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) {
    db.listSet(new String(encode(name.getBytes)), index, new String(elem))
  }
  
  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = {
    db.listIndex(new String(encode(name.getBytes)), index) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " does not have element at " + index)
      case Some(e) => e.getBytes
    }
  }
  
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    /**
     * <tt>count</tt> is the max number of results to return. Start with
     * <tt>start</tt> or 0 (if <tt>start</tt> is not defined) and go until
     * you hit <tt>finish</tt> or <tt>count</tt>.
     */
    val s = if (start.isDefined) start.get else 0
    val cnt =
      if (finish.isDefined) {
        val f = finish.get
        if (f >= s) Math.min(count, (f - s)) else count
      }
      else count
    db.listRange(new String(encode(name.getBytes)), s, s + cnt - 1) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " does not have elements in the range specified")
      case Some(l) =>
        l map (_.getBytes)
    }
  }
  
  def getVectorStorageSizeFor(name: String): Int = {
    db.listLength(new String(encode(name.getBytes))) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " not present")
      case Some(l) => l
    }
  }
  
  def insertRefStorageFor(name: String, element: Array[Byte]) {
    db.set(new String(encode(name.getBytes)), new String(element))
  }
  
  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    db.get(new String(encode(name.getBytes))) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " not present")
      case Some(s) => Some(s.getBytes)
    }
  }

  // add to the end of the queue
  def enqueue(name: String, item: Array[Byte]): Boolean = {
    db.pushTail(new String(encode(name.getBytes)), new String(item))
  }

  // pop from the front of the queue
  def dequeue(name: String): Option[Array[Byte]] = {
    db.popHead(new String(encode(name.getBytes))) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " not present")
      case Some(s) =>
        Some(s.getBytes)
    }
  }
  
  // get the size of the queue
  def size(name: String): Int = {
    db.listLength(new String(encode(name.getBytes))) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " not present")
      case Some(l) => l
    }
  }
  
  // return an array of items currently stored in the queue
  // start is the item to begin, count is how many items to return
  def peek(name: String, start: Int, count: Int): List[Array[Byte]] = count match {
    case 1 =>
      db.listIndex(new String(encode(name.getBytes)), start) match {
        case None =>
          throw new java.util.NoSuchElementException("No element at " + start)
        case Some(s) =>
          List(s.getBytes)
      }
    case n =>
      db.listRange(new String(encode(name.getBytes)), start, start + count - 1) match {
        case None => 
          throw new java.util.NoSuchElementException(
            "No element found between " + start + " and " + (start + count - 1))
        case Some(es) =>
          es.map(_.getBytes)
      }
  }
  
  // completely delete the queue
  def remove(name: String): Boolean = {
    db.delete(new String(encode(name.getBytes)))
  }
  
  // add item to sorted set identified by name
  def zadd(name: String, zscore: String, item: Array[Byte]): Boolean = {
    db.zAdd(new String(encode(name.getBytes)), zscore, new String(item))
  }
  
  // remove item from sorted set identified by name
  def zrem(name: String, item: Array[Byte]): Boolean = {
    db.zRem(new String(encode(name.getBytes)), new String(item))
  }
  
  // cardinality of the set identified by name
  def zcard(name: String): Int = {
    db.zCard(new String(encode(name.getBytes))) match {
      case None =>
        throw new java.util.NoSuchElementException(name + " not present")
      case Some(l) => l
    }
  }
  
  def zscore(name: String, item: Array[Byte]): String = {
    db.zScore(new String(encode(name.getBytes)), new String(item)) match {
      case None =>
        throw new java.util.NoSuchElementException(new String(item) + " not present")
      case Some(s) => s
    }
  }
  
  def zrange(name: String, start: Int, end: Int): List[Array[Byte]] = {
    db.zRange(new String(encode(name.getBytes)), start.toString, end.toString, SocketOperations.ASC, false) match {
      case None => 
        throw new java.util.NoSuchElementException(name + " not present")
      case Some(s) =>
        s.map(_.getBytes)
    }
  }
  
  def flushDB = db.flushDb
}
