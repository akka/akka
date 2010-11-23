/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.redis

import akka.stm._
import akka.persistence.common._
import akka.util.Logging
import akka.config.Config.config

import com.redis._

trait Base64StringEncoder {
  def byteArrayToString(bytes: Array[Byte]): String
  def stringToByteArray(str: String): Array[Byte]
}

object CommonsCodec {
  import org.apache.commons.codec.binary.Base64
  import org.apache.commons.codec.binary.Base64._

  val b64 = new Base64(true)

  trait CommonsCodecBase64StringEncoder {
    def byteArrayToString(bytes: Array[Byte]) = encodeBase64URLSafeString(bytes)
    def stringToByteArray(str: String) = b64.decode(str)
  }

  object Base64StringEncoder extends Base64StringEncoder with CommonsCodecBase64StringEncoder
}

import CommonsCodec._
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
  val nodes = config.getList("akka.persistence.redis.cluster")

  def connect() =
    nodes match {
      case Seq() =>
        // no cluster defined
        val REDIS_SERVER_HOSTNAME = config.getString("akka.persistence.redis.hostname", "127.0.0.1")
        val REDIS_SERVER_PORT = config.getInt("akka.persistence.redis.port", 6379)
        new RedisClient(REDIS_SERVER_HOSTNAME, REDIS_SERVER_PORT)

      case s =>
        // with cluster
        import com.redis.cluster._
        log.info("Running on Redis cluster")
        new RedisCluster(nodes: _*) {
          val keyTag = Some(NoOpKeyTag)
        }
    }

  var db = connect()

  /**
   * Map storage in Redis.
   * <p/>
   * Maps are stored as key/value pairs in redis.
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
    "%s:%s".format(name, new String(key))
  }

  private [this] def makeKeyFromRedisKey(redisKey: String) = withErrorHandling {
    val nk = redisKey.split(':')
    (nk(0), nk(1).getBytes)
  }

  private [this] def mset(entries: List[(String, String)]): Unit = withErrorHandling {
    entries.foreach {e: (String, String) =>
      db.set(e._1, e._2)
    }
  }

  def removeMapStorageFor(name: String): Unit = withErrorHandling {
    db.keys("%s:*".format(name)) match {
      case None =>
        throw new NoSuchElementException(name + " not present")
      case Some(keys) =>
        keys.foreach(k => db.del(k.get))
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = withErrorHandling {
    db.del(makeRedisKey(name, key))
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = withErrorHandling {
    db.get(makeRedisKey(name, key))
      .map(stringToByteArray(_))
      .orElse(throw new NoSuchElementException(new String(key) + " not present"))
    }

  def getMapStorageSizeFor(name: String): Int = withErrorHandling {
    db.keys("%s:*".format(name)).map(_.length).getOrElse(0)
  }

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = withErrorHandling {
    db.keys("%s:*".format(name))
      .map { keys =>
        keys.map(key => (makeKeyFromRedisKey(key.get)._2, stringToByteArray(db.get(key.get).get))).toList
      }.getOrElse {
        throw new NoSuchElementException(name + " not present")
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
    db.lpush(name, byteArrayToString(element))
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]): Unit = withErrorHandling {
    elements.foreach(insertVectorStorageEntryFor(name, _))
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]): Unit = withErrorHandling {
    db.lset(name, index, byteArrayToString(elem))
  }

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = withErrorHandling {
    db.lindex(name, index)
      .map(stringToByteArray(_))
      .getOrElse {
        throw new NoSuchElementException(name + " does not have element at " + index)
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
    db.lrange(name, s, s + cnt - 1) match {
      case None =>
        throw new NoSuchElementException(name + " does not have elements in the range specified")
      case Some(l) =>
        l map (e => stringToByteArray(e.get))
    }
  }

  def getVectorStorageSizeFor(name: String): Int = withErrorHandling {
    db.llen(name).getOrElse { throw new NoSuchElementException(name + " not present") }
  }

  def insertRefStorageFor(name: String, element: Array[Byte]): Unit = withErrorHandling {
    db.set(name, byteArrayToString(element))
  }

  def insertRefStorageFor(name: String, element: String): Unit = withErrorHandling {
    db.set(name, element)
  }

  def getRefStorageFor(name: String): Option[Array[Byte]] = withErrorHandling {
    db.get(name)
      .map(stringToByteArray(_))
  }

  // add to the end of the queue
  def enqueue(name: String, item: Array[Byte]): Option[Int] = withErrorHandling {
    db.rpush(name, byteArrayToString(item))
  }

  // pop from the front of the queue
  def dequeue(name: String): Option[Array[Byte]] = withErrorHandling {
    db.lpop(name)
      .map(stringToByteArray(_))
      .orElse {
        throw new NoSuchElementException(name + " not present")
      }
  }

  // get the size of the queue
  def size(name: String): Int = withErrorHandling {
    db.llen(name).getOrElse { throw new NoSuchElementException(name + " not present") }
  }

  // return an array of items currently stored in the queue
  // start is the item to begin, count is how many items to return
  def peek(name: String, start: Int, count: Int): List[Array[Byte]] = withErrorHandling {
    count match {
      case 1 =>
        db.lindex(name, start) match {
          case None =>
            throw new NoSuchElementException("No element at " + start)
          case Some(s) =>
            List(stringToByteArray(s))
        }
      case n =>
        db.lrange(name, start, start + count - 1) match {
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
    db.del(name).map { case 1 => true }.getOrElse(false)
  }

  // add item to sorted set identified by name
  def zadd(name: String, zscore: String, item: Array[Byte]): Boolean = withErrorHandling {
    db.zadd(name, zscore, byteArrayToString(item))
      .map { e =>
        e match {
          case 1 => true
          case _ => false
        }
      }.getOrElse(false)
  }

  // remove item from sorted set identified by name
  def zrem(name: String, item: Array[Byte]): Boolean = withErrorHandling {
    db.zrem(name, byteArrayToString(item))
      .map { e =>
        e match {
          case 1 => true
          case _ => false
        }
      }.getOrElse(false)
  }

  // cardinality of the set identified by name
  def zcard(name: String): Int = withErrorHandling {
    db.zcard(name).getOrElse { throw new NoSuchElementException(name + " not present") }
  }

  def zscore(name: String, item: Array[Byte]): Option[Float] = withErrorHandling {
    db.zscore(name, byteArrayToString(item)).map(_.toFloat)
  }

  def zrange(name: String, start: Int, end: Int): List[Array[Byte]] = withErrorHandling {
    db.zrange(name, start.toString, end.toString, RedisClient.ASC, false)
      .map(_.map(e => stringToByteArray(e.get)))
      .getOrElse {
        throw new NoSuchElementException(name + " not present")
      }
  }

  def zrangeWithScore(name: String, start: Int, end: Int): List[(Array[Byte], Float)] = withErrorHandling {
    db.zrangeWithScore(name, start.toString, end.toString, RedisClient.ASC)
      .map(_.map { case (elem, score) => (stringToByteArray(elem.get), score.get.toFloat) })
      .getOrElse {
        throw new NoSuchElementException(name + " not present")
      }
  }

  def flushDB = withErrorHandling(db.flushdb)

  private def withErrorHandling[T](body: => T): T = {
    try {
      body
    } catch {
      case e: RedisConnectionException => {
        db = connect()
        body
      }
      case e: java.lang.NullPointerException =>
        throw new StorageException("Could not connect to Redis server")
      case e =>
        throw new StorageException("Error in Redis: " + e.getMessage)
    }
  }
}
