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
import collection.mutable.{Set, HashSet, ArrayBuffer}
import java.lang.String
import voldemort.utils.ByteUtils
import collection.immutable.{SortedSet, TreeSet}
import voldemort.versioning.Versioned
import java.util.Map
import collection.JavaConversions


private[akka] object VoldemortStorageBackend extends
MapStorageBackend[Array[Byte], Array[Byte]] with
        VectorStorageBackend[Array[Byte]] with
        RefStorageBackend[Array[Byte]] with
        Logging {

  /**
   * Concat the owner+key+lenght of owner so owned data will be colocated
   * Store the length of owner as last byte to work around the rare case
   * where ownerbytes1 + keybytes1 == ownerbytes2 + keybytes2 but ownerbytes1 != ownerbytes2
   */
  private def mapKey(owner: String, key: Array[Byte]): Array[Byte] = {
    val ownerBytes: Array[Byte] = owner.getBytes("UTF-8")
    val ownerLenghtByte = ownerBytes.length.byteValue
    val theMapKey = new Array[Byte](ownerBytes.length + key.length + 1)
    System.arraycopy(ownerBytes, 0, theMapKey, 0, ownerBytes.length)
    System.arraycopy(key, 0, theMapKey, ownerBytes.length, key.length)
    theMapKey.update(theMapKey.length - 1, ownerLenghtByte)
    theMapKey
  }

  var refClient: StoreClient[String, Array[Byte]] = null
  var mapKeyClient: StoreClient[String, SortedSet[Array[Byte]]] = null
  var mapValueClient: StoreClient[Array[Byte], Array[Byte]] = null

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
    val allkeys: SortedSet[Array[Byte]] = mapKeyClient.getValue(name, new TreeSet[Array[Byte]])
    val range = allkeys.rangeImpl(start, finish).take(count)
    getKeyValues(range)
  }

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = {
    val keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    getKeyValues(keys)
  }

  private def getKeyValues(keys: SortedSet[Array[Byte]]): List[(Array[Byte], Array[Byte])] = {
    val all: Map[Array[Byte], Versioned[Array[Byte]]] = mapValueClient.getAll(JavaConversions.asIterable(keys))
    JavaConversions.asMap(all).foldLeft(new ArrayBuffer[(Array[Byte], Array[Byte])]) {
      (buf, keyVal) => {
        keyVal match {
          case (key, versioned) => {
            buf += key -> versioned.getValue
          }
        }
        buf
      }
    }.toList
  }

  def getMapStorageSizeFor(name: String): Int = {
    val keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys.size
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    val result: Array[Byte] = mapValueClient.getValue(mapKey(name, key))
    result match {
      case null => None
      case _ => Some(result)
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]) = {
    var keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys -= key
    mapKeyClient.put(name, keys)
    mapValueClient.delete(mapKey(name, key))
  }


  def removeMapStorageFor(name: String) = {
    val keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys.foreach {
      key =>
        mapValueClient.delete(mapKey(name, key))
    }
    mapKeyClient.delete(name)
  }

  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) = {
    mapValueClient.put(mapKey(name, key), value)
    var keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys += key
    mapKeyClient.put(name, keys)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) = {
    val newKeys = entries.map {
      case (key, value) => {
        mapValueClient.put(mapKey(name, key), value)
        key
      }
    }
    var keys = mapKeyClient.getValue(name, new TreeSet[Array[Byte]]())
    keys ++= newKeys
    mapKeyClient.put(name, keys)
  }


  def getVectorStorageSizeFor(name: String): Int = 0

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = null

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = null

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = null

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = null

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = null


}