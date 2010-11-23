/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.memcached

import akka.persistence.common._
import akka.config.Config.config
import net.spy.memcached._
import net.spy.memcached.transcoders._
import collection.JavaConversions
import java.lang.String
import collection.immutable.{TreeMap, Iterable}
import java.util.concurrent.{TimeoutException, Future, TimeUnit}

private[akka] object MemcachedStorageBackend extends CommonStorageBackend {

  import CommonStorageBackendAccess._
  import CommonStorageBackend._
  import KVStorageBackend._
  import org.apache.commons.codec.binary.Base64

  val clientAddresses = config.getString("akka.persistence.memcached.client.addresses", "localhost:11211")
  val factory = new KetamaConnectionFactory
  val client = new MemcachedClient(factory, AddrUtil.getAddresses(clientAddresses))
  val base64 = new Base64(76, Array.empty[Byte], true)

  def queueAccess = new MemcachedAccess("Q")

  def mapAccess = new MemcachedAccess("M")

  def vectorAccess = new MemcachedAccess("V")

  def refAccess = new MemcachedAccess("R")

  private[akka] class MemcachedAccess(val accessType: String) extends KVStorageBackendAccess {

    val typeBytes = stringToByteArray(accessType)

    private def encodeKey(key: Array[Byte]): Array[Byte] = {
      val newkey = new Array[Byte](key.length + typeBytes.length)
      System.arraycopy(key, 0, newkey, 0, key.length)
      System.arraycopy(typeBytes, 0, newkey, key.length, typeBytes.length)
      newkey
    }

    private def keyStr(key: Array[Byte]): String = {
      base64.encodeToString(key)
    }

    override def decodeMapKey(owner: String, key: Array[Byte]) = {
      val newkey = new Array[Byte](key.length - typeBytes.length)
      System.arraycopy(key, 0, newkey, 0, newkey.length)
      super.decodeMapKey(owner, newkey)
    }

    def drop() = client.flush()

    def delete(key: Array[Byte]) = {
      retry(5, (1L, TimeUnit.SECONDS), false) {
        client.delete(keyStr(encodeKey(key)))
      }
    }

    def getAll(keys: Iterable[Array[Byte]]) = {
      val jmap = client.getBulk(JavaConversions.asJavaList(keys.map{
        k: Array[Byte] =>
          keyStr(encodeKey(k))
      }.toList))
      JavaConversions.asScalaMap(jmap).map{
        kv => kv match {
          case (key, value) => (base64.decode(key) -> value.asInstanceOf[Array[Byte]])
        }
      }
    }

    def get(key: Array[Byte], default: Array[Byte]) = {
      Option(client.get(keyStr(encodeKey(key)))) match {
        case Some(value) => value.asInstanceOf[Array[Byte]]
        case None => default
      }
    }

    def get(key: Array[Byte]) = get(key, null)


    def put(key: Array[Byte], value: Array[Byte]) = {
      retry(5, (1L, TimeUnit.SECONDS), true) {
        client.set(keyStr(encodeKey(key)), Integer.MAX_VALUE, value)
      }

    }

    private def retry(tries: Int, waitFor: (Long, TimeUnit), tillTrue: Boolean)(action: => Future[java.lang.Boolean]): Unit = {
      if (tries == 0) {
        throw new TimeoutException("Exahusted all retries performing an operation on memcached")
      } else {
        val future = action
        try
        {
          if (future.get(waitFor._1, waitFor._2).equals(false) && tillTrue) {
            log.debug("memcached future returned false, operation failed. retrying")
            retry(tries - 1, waitFor, tillTrue)(action)
          }
        } catch {
          case te: TimeoutException => {
            log.debug("memcached future timed out. retrying")
            retry(tries - 1, waitFor, tillTrue)(action)
          }
        }
      }
    }

  }


}
