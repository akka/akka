/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.simpledb

import akka.persistence.common._
import akka.config.Config.config
import net.spy.memcached._
import net.spy.memcached.transcoders._
import collection.JavaConversions
import java.lang.String
import collection.immutable.{TreeMap, Iterable}
import java.util.concurrent.{TimeoutException, Future, TimeUnit}
import org.sublime.amazon.simpleDB.api.SimpleDBAccount

private[akka] object SimpledbStorageBackend extends CommonStorageBackend {

  import CommonStorageBackendAccess._
  import CommonStorageBackend._
  import KVStorageBackend._
  import org.apache.commons.codec.binary.Base64

  val base64 = new Base64(76, Array.empty[Byte], true)
  val key = config.getString("akka.storage.simpledb.account.key", "foo")
  val keyId = config.getString("akka.storage.simpledb.account.keyId", "bar")
  val refDomain = config.getString("akka.storage.simpledb.domain.ref", "ref")
  val mapDomain = config.getString("akka.storage.simpledb.domain.map", "map")
  val queueDomain = config.getString("akka.storage.simpledb.domain.queue", "queue")
  val vectorDomain = config.getString("akka.storage.simpledb.domain.vector", "vector")

  val account = new SimpleDBAccount(key,keyId)

  def queueAccess = new SimpledbAccess(queueDomain)

  def mapAccess = new SimpledbAccess(mapDomain)

  def vectorAccess = new SimpledbAccess(vectorDomain)

  def refAccess = new SimpledbAccess(refDomain)

  private[akka] class SimpledbAccess(val domainName: String) extends CommonStorageBackendAccess {

    val domain = account domain(domainName)
    domain create

    def drop() = domain delete

    def getAll(owner: String, keys: Iterable[Array[Byte]]) = null

    def delete(owner: String, key: Array[Byte]) = null

    def put(owner: String, key: Array[Byte], value: Array[Byte]) = null

    def getValue(owner: String, key: Array[Byte], default: Array[Byte]) = {
      domain.item(owner)
    }
  }


}