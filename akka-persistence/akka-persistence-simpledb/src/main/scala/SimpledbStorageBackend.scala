/**
 *  Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.simpledb

import akka.persistence.common._
import akka.config.Config.config
import java.lang.String
import java.util.{List => JList, ArrayList => JAList}

import collection.immutable.{HashMap, Iterable}
import collection.mutable.{HashMap => MMap}

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.simpledb.AmazonSimpleDBClient
import com.amazonaws.services.simpledb.model._
import collection.{JavaConversions, Map}

private[akka] object SimpledbStorageBackend extends CommonStorageBackend {

  import org.apache.commons.codec.binary.Base64

  val seperator = "\r\n"
  val seperatorBytes = seperator.getBytes("UTF-8")
  val base64 = new Base64(1024, seperatorBytes, true)
  val base64key = new Base64(1024, Array.empty[Byte], true)
  val id = config.getString("akka.storage.simpledb.account.id")
  val secretKey = config.getString("akka.storage.simpledb.account.secretKey")
  val refDomain = config.getString("akka.storage.simpledb.domain.ref", "ref")
  val mapDomain = config.getString("akka.storage.simpledb.domain.map", "map")
  val queueDomain = config.getString("akka.storage.simpledb.domain.queue", "queue")
  val vectorDomain = config.getString("akka.storage.simpledb.domain.vector", "vector")
  val credentials = new BasicAWSCredentials(id, secretKey);
  val client = new AmazonSimpleDBClient(credentials)

  def queueAccess = queue

  def mapAccess = map

  def vectorAccess = vector

  def refAccess = ref

  val queue = new SimpledbAccess(queueDomain)

  val map = new SimpledbAccess(mapDomain)

  val vector = new SimpledbAccess(vectorDomain)

  val ref = new SimpledbAccess(refDomain)

  private[akka] class SimpledbAccess(val domainName: String) extends KVStorageBackendAccess {

    var created = false

    def getClient(): AmazonSimpleDBClient = {
      if (!created) {
        client.createDomain(new CreateDomainRequest(domainName))
        created = true
      }
      client
    }


    def drop(): Unit = {
      created = false
      client.deleteDomain(new DeleteDomainRequest(domainName))
    }

    def delete(key: Array[Byte]): Unit = getClient.deleteAttributes(new DeleteAttributesRequest(domainName, encodeAndValidateKey(key)))

    def getAll(keys: Iterable[Array[Byte]]): Map[Array[Byte], Array[Byte]] = {
      keys.foldLeft(new HashMap[Array[Byte], Array[Byte]]) {
        (map, key) => {
          val value = getValue(key)
          if (value != null) {
            map + (key -> getValue(key))
          } else {
            map
          }
        }
      }
    }

    def getValue(key: Array[Byte], default: Array[Byte]): Array[Byte] = {
      val req = new GetAttributesRequest(domainName, encodeAndValidateKey(key)).withConsistentRead(true)
      val resp = getClient.getAttributes(req)
      recomposeValue(resp.getAttributes) match {
        case Some(value) => value
        case None => default
      }
    }

    def getValue(key: Array[Byte]): Array[Byte] = getValue(key, null)

    def put(key: Array[Byte], value: Array[Byte]): Unit = {
      val req = new PutAttributesRequest(domainName, encodeAndValidateKey(key), decomposeValue(value))
      getClient.putAttributes(req)
    }

    def encodeAndValidateKey(key: Array[Byte]): String = {
      val keystr = base64key.encodeToString(key)
      if (keystr.length > 1024) {
        throw new IllegalArgumentException("encoded key was longer than 1024 bytes")
      }
      keystr
    }

    def decomposeValue(value: Array[Byte]): JList[ReplaceableAttribute] = {
      val encoded = base64.encodeToString(value)
      val strings = encoded.split(seperator)
      if (strings.size > 255) {
        throw new IllegalArgumentException("The decomposed value is larger than 255K")
      }

      val list: JAList[ReplaceableAttribute] = strings.zipWithIndex.foldLeft(new JAList[ReplaceableAttribute]) {
        (list, zip) => {
          zip match {
            case (encode, index) => {
              list.add(new ReplaceableAttribute(index.toString, encode, true))
              list
            }
          }
        }
      }
      list.add(new ReplaceableAttribute("size", list.size.toString, true))
      list
    }

    def recomposeValue(atts: JList[Attribute]): Option[Array[Byte]] = {
      val itemSnapshot = JavaConversions.asIterable(atts).foldLeft(new MMap[String, String]) {
        (map, att) => {
          map += (att.getName -> att.getValue)
        }
      }
      itemSnapshot.get("size") match {
        case Some(strSize) => {
          val size = Integer.parseInt(strSize)
          val encoded = (0 until size).map(_.toString).map(itemSnapshot.get(_).get).reduceLeft[String] {
            (acc, str) => acc + seperator + str
          }
          Some(base64.decode(encoded))
        }
        case None => None
      }
    }

  }


}