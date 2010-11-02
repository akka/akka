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
import java.util.concurrent.TimeUnit

private[akka] object MemcachedStorageBackend extends CommonStorageBackend {

  import CommonStorageBackendAccess._
  import CommonStorageBackend._
  import KVStorageBackend._
  import org.apache.commons.codec.binary.Base64

  val clientAddresses = config.getString("akka.storage.memcached.client.addresses", "localhost:11211")
  val factory = new ConnectionFactoryBuilder().setTranscoder(new SerializingTranscoder()).setProtocol(ConnectionFactoryBuilder.Protocol.BINARY).build
  val client = new MemcachedClient(factory, AddrUtil.getAddresses(clientAddresses))
  val base64 = new Base64(76, Array.empty[Byte], true)

  def queueAccess = new MemcachedAccess("que")

  def mapAccess = new MemcachedAccess("map")

  def vectorAccess = new MemcachedAccess("vec")

  def refAccess = new MemcachedAccess("ref")

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

    override def decodeKey(owner: String, key: Array[Byte]) = {
      val newkey = new Array[Byte](key.length - typeBytes.length)
      System.arraycopy(key, 0, newkey, 0, newkey.length)
      super.decodeKey(owner, newkey)
    }

    def drop() = client.flush()

    def delete(key: Array[Byte]) = {
      val deleted = client.delete(keyStr(encodeKey(key))).get(5L, TimeUnit.SECONDS);
      ()
    }

    def getAll(keys: Iterable[Array[Byte]]) = {
      val jmap = client.getBulk(JavaConversions.asList(keys.map{
        k: Array[Byte] =>
          keyStr(encodeKey(k))
      }.toList))
      JavaConversions.asMap(jmap).map{
        kv => kv match {
          case (key, value) => (base64.decode(key) -> value.asInstanceOf[Array[Byte]])
        }
      }
    }

    def getValue(key: Array[Byte], default: Array[Byte]) = {
      Option(client.get(keyStr(encodeKey(key)))) match {
        case Some(value) => value.asInstanceOf[Array[Byte]]
        case None => default
      }
    }

    def getValue(key: Array[Byte]) = getValue(key, null)


    def put(key: Array[Byte], value: Array[Byte]) = {
      client.set(keyStr(encodeKey(key)), Integer.MAX_VALUE, value).get(5L, TimeUnit.SECONDS);
      ()
    }

  }


}