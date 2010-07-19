/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.redis

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.Config.config

import com.redis._

trait Base64Encoder {
  def encode(bytes: Array[Byte]): Array[Byte]
  def decode(bytes: Array[Byte]): Array[Byte]
}

trait Base64StringEncoder {
  def byteArrayToString(bytes: Array[Byte]): String
  def stringToByteArray(str: String): Array[Byte]
}

trait NullBase64 {
  def encode(bytes: Array[Byte]): Array[Byte] = bytes
  def decode(bytes: Array[Byte]): Array[Byte] = bytes
}

object CommonsCodec {
  import org.apache.commons.codec.binary.Base64
  import org.apache.commons.codec.binary.Base64._

  val b64 = new Base64(true)

  trait CommonsCodecBase64 {
    def encode(bytes: Array[Byte]): Array[Byte] = encodeBase64(bytes)
    def decode(bytes: Array[Byte]): Array[Byte] = decodeBase64(bytes)
  }

  object Base64Encoder extends Base64Encoder with CommonsCodecBase64

  trait CommonsCodecBase64StringEncoder {
    def byteArrayToString(bytes: Array[Byte]) = encodeBase64URLSafeString(bytes)
    def stringToByteArray(str: String) = b64.decode(str)
  }

  object Base64StringEncoder extends Base64StringEncoder with CommonsCodecBase64StringEncoder
}

import CommonsCodec._
import CommonsCodec.Base64Encoder._
import CommonsCodec.Base64StringEncoder._

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

  // need an explicit definition in akka-conf
  val nodes = config.getList("akka.storage.redis.cluster")

  val db =
    nodes match {
      case Seq() =>
        // no cluster defined
        val REDIS_SERVER_HOSTNAME = config.getString("akka.storage.redis.hostname", "127.0.0.1")
        val REDIS_SERVER_PORT = config.getInt("akka.storage.redis.port", 6379)
        new RedisClient(REDIS_SERVER_HOSTNAME, REDIS_SERVER_PORT)

      case s =>
        // with cluster
        import com.redis.cluster._
        log.info("Running on Redis cluster")
        new RedisCluster(nodes: _*) {
          val keyTag = Some(NoOpKeyTag)
        }
    }

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
  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]): Unit = withErrorHandling {
    insertMapStorageEntriesFor(name, List((key, value)))
  }

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[Array[Byte], Array[Byte]]]): Unit = withErrorHandling {
    mset(entries.map(e =>
          (makeRedisKey(name, e._1), byteArrayToString(e._2))))
  }

  /**
   * Make a redis key from an Akka Map key.
   * <p/>
   * The key is made as follows:
   * <li>redis key is composed of 2 parts: the transaction id and the map key separated by :</li>
   * <li>: is chosen since it cannot appear in base64 encoding charset</li>
   * <li>both parts of the key need to be based64 encoded since there can be spaces within each of them</li>
   */
  private [this] def makeRedisKey(name: String, key: Array[Byte]): String = withErrorHandling {
    "%s:%s".format(new String(encode(name.getBytes)), new String(encode(key)))
  }

  private [this] def makeKeyFromRedisKey(redisKey: String) = withErrorHandling {
    val nk = redisKey.split(':').map{e: String => decode(e.getBytes)}
    (nk(0), nk(1))
  }

  private [this] def mset(entries: List[(String, String)]): Unit = withErrorHandling {
    entries.foreach {e: (String, String) =>
      db.set(e._1, e._2)
    }
  }

  def removeMapStorageFor(name: String): Unit = withErrorHandling {
    db.keys("%s:*".format(new String(encode(name.getBytes)))) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(keys) =>
        keys.foreach(db.del(_))
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = withErrorHandling {
    db.del(makeRedisKey(name, key))
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = withErrorHandling {
    db.get(makeRedisKey(name, key)) match {
      case None =>
        throw new NoSuchElementException(new String(key) + " not present")
      case Some(s) => Some(stringToByteArray(s))
    }
  }

  def getMapStorageSizeFor(name: String): Int = withErrorHandling {
    db.keys("%s:*".format(new String(encode(name.getBytes)))) match {
      case None => 0
      case Some(keys) =>
        keys.length
    }
  }

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = withErrorHandling {
    db.keys("%s:*".format(new String(encode(name.getBytes)))) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(keys) =>
        keys.map(key => (makeKeyFromRedisKey(key)._2, stringToByteArray(db.get(key).get))).toList
    }
  }

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]],
                            finish: Option[Array[Byte]],
                            count: Int): List[(Array[Byte], Array[Byte])] = withErrorHandling {

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
                   .iterator
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

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]): Unit = withErrorHandling {
    db.lpush(new String(encode(name.getBytes)), byteArrayToString(element))
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]): Unit = withErrorHandling {
    elements.foreach(insertVectorStorageEntryFor(name, _))
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]): Unit = withErrorHandling {
    db.lset(new String(encode(name.getBytes)), index, byteArrayToString(elem))
  }

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = withErrorHandling {
    db.lindex(new String(encode(name.getBytes)), index) match {
      case None =>
        throw new NoSuchElementException(name + " does not have element at " + index)
      case Some(e) =>
        stringToByteArray(e)
    }
  }

  /**
   * if <tt>start</tt> and <tt>finish</tt> both are defined, ignore <tt>count</tt> and
   * report the range [start, finish)
   * if <tt>start</tt> is not defined, assume <tt>start</tt> = 0
   * if <tt>start</tt> == 0 and <tt>finish</tt> == 0, return an empty collection
   */
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = withErrorHandling {
    val s = if (start.isDefined) start.get else 0
    val cnt =
      if (finish.isDefined) {
        val f = finish.get
        if (f >= s) (f - s) else count
      }
      else count
    if (s == 0 && cnt == 0) List()
    else
    db.lrange(new String(encode(name.getBytes)), s, s + cnt - 1) match {
      case None =>
        throw new NoSuchElementException(name + " does not have elements in the range specified")
      case Some(l) =>
        l map ( e => stringToByteArray(e.get))
    }
  }

  def getVectorStorageSizeFor(name: String): Int = withErrorHandling {
    db.llen(new String(encode(name.getBytes))) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(l) =>
        l
    }
  }

  def insertRefStorageFor(name: String, element: Array[Byte]): Unit = withErrorHandling {
    db.set(new String(encode(name.getBytes)), byteArrayToString(element))
  }

  def insertRefStorageFor(name: String, element: String): Unit = withErrorHandling {
    db.set(new String(encode(name.getBytes)), element)
  }

  def getRefStorageFor(name: String): Option[Array[Byte]] = withErrorHandling {
    db.get(new String(encode(name.getBytes))) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(s) => Some(stringToByteArray(s))
    }
  }

  // add to the end of the queue
  def enqueue(name: String, item: Array[Byte]): Boolean = withErrorHandling {
    db.rpush(new String(encode(name.getBytes)), byteArrayToString(item))
  }

  // pop from the front of the queue
  def dequeue(name: String): Option[Array[Byte]] = withErrorHandling {
    db.lpop(new String(encode(name.getBytes))) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(s) => Some(stringToByteArray(s))
    }
  }

  // get the size of the queue
  def size(name: String): Int = withErrorHandling {
    db.llen(new String(encode(name.getBytes))) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(l) => l
    }
  }

  // return an array of items currently stored in the queue
  // start is the item to begin, count is how many items to return
  def peek(name: String, start: Int, count: Int): List[Array[Byte]] = withErrorHandling {
    count match {
      case 1 =>
        db.lindex(new String(encode(name.getBytes)), start) match {
          case None =>
            throw new NoSuchElementException("No element at " + start)
          case Some(s) =>
            List(stringToByteArray(s))
        }
      case n =>
        db.lrange(new String(encode(name.getBytes)), start, start + count - 1) match {
          case None =>
            throw new NoSuchElementException(
              "No element found between " + start + " and " + (start + count - 1))
          case Some(es) =>
            es.map(e => stringToByteArray(e.get))
        }
     }
  }

  // completely delete the queue
  def remove(name: String): Boolean = withErrorHandling {
    db.del(new String(encode(name.getBytes))) match {
      case Some(1) => true
      case _ => false
    }
  }

  // add item to sorted set identified by name
  def zadd(name: String, zscore: String, item: Array[Byte]): Boolean = withErrorHandling {
    db.zadd(new String(encode(name.getBytes)), zscore, byteArrayToString(item)) match {
      case Some(1) => true
      case _ => false
    }
  }

  // remove item from sorted set identified by name
  def zrem(name: String, item: Array[Byte]): Boolean = withErrorHandling {
    db.zrem(new String(encode(name.getBytes)), byteArrayToString(item)) match {
      case Some(1) => true
      case _ => false
    }
  }

  // cardinality of the set identified by name
  def zcard(name: String): Int = withErrorHandling {
    db.zcard(new String(encode(name.getBytes))) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(l) => l
    }
  }

  def zscore(name: String, item: Array[Byte]): Option[Float] = withErrorHandling {
    db.zscore(new String(encode(name.getBytes)), byteArrayToString(item)) match {
      case Some(s) => Some(s.toFloat)
      case None => None
    }
  }

  def zrange(name: String, start: Int, end: Int): List[Array[Byte]] = withErrorHandling {
    db.zrange(new String(encode(name.getBytes)), start.toString, end.toString, RedisClient.ASC, false) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(s) =>
        s.map(e => stringToByteArray(e.get))
    }
  }

  def zrangeWithScore(name: String, start: Int, end: Int): List[(Array[Byte], Float)] = withErrorHandling {
    db.zrangeWithScore(
      new String(encode(name.getBytes)), start.toString, end.toString, RedisClient.ASC) match {
        case None =>
          throw new NoSuchElementException(name + " not present")
        case Some(l) =>
          l.map{ case (elem, score) => (stringToByteArray(elem.get), score.get.toFloat) }
    }
  }

  def flushDB = withErrorHandling(db.flushdb)

  private def withErrorHandling[T](body: => T): T = {
    try {
      body
    } catch {
      case e: java.lang.NullPointerException =>
        throw new StorageException("Could not connect to Redis server")
      case e =>
        throw new StorageException("Error in Redis: " + e.getMessage)
    }
  }
}
