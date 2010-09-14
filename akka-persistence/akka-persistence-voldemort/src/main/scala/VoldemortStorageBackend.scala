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


private[akka] object VoldemortStorageBackend extends
MapStorageBackend[Array[Byte], Array[Byte]] with
        VectorStorageBackend[Array[Byte]] with
        RefStorageBackend[Array[Byte]] with
        Logging {

  /**
   * Concat the owner+key+lenght of owner so owned data will be colocated
   * Store the length of owner as last byte to work aroune the rarest case
   * where ownerbytes1 + keybytes1 == ownerbytes2 + keybytes2 but ownerbytes1 != ownerbytes2
   */
  private def mapKey(owner: String, key: Array[Byte]): Array[Byte] = {
    val ownerBytes: Array[Byte] = owner.getBytes("UTF-8")
    val ownerLenghtByte = ownerBytes.length.byteValue
    val mapKey = new Array[Byte](ownerBytes.length + key.length + 1)
    System.arraycopy(ownerBytes, 0, mapKey, 0, ownerBytes.length)
    System.arraycopy(key, 0, mapKey, ownerBytes.length, key.length)
    mapKey.update(mapKey.length - 1) = ownerLenghtByte
  }

  var refClient: StoreClient
  var mapKeyClient: StoreClient
  var mapValueClient: StoreClient


  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    val result: Array[Byte] = refClient.get(RefKey(name).key)
    result match {
      case null => None
      case _ => Some(result)
    }
  }

  def insertRefStorageFor(name: String, element: Array[Byte]) = {
    refClient.put(RefKey(name).key, element)
  }

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int): List[(Array[Byte], Array[Byte])] = {

  }

  def getMapStorageFor(name: String): List[(Array[Byte], Array[Byte])] = {
    val keys: Set[Array[Byte]] = mapKeyClient.getValue(name, new HashSet[Array[Byte]](0))
    val entries: ArrayBuffer[(Array[Byte], Array[Byte])] = new ArrayBuffer
    keys.foreach {
      entries += (_, mapValueClient.getValue(mapKey(name, _)))
    }
    entries.toList
  }

  def getMapStorageSizeFor(name: String): Int = {
    val keys: Set[Array[Byte]] = mapKeyClient.getValue(name, new HashSet[Array[Byte]](0))
    keys.size
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    val result: Array[Byte] = mapValueClient.get(mapKey(name, key))
    result match {
      case null => None
      case _ => Some(result)
    }
  }

  def removeMapStorageFor(name: String, key: Array[Byte]) = {
    val keys: Set[Array[Byte]] = mapKeyClient.getValue(name, new HashSet[Array[Byte]](0))
    keys -= key
    mapKeyClient.put(name, keys)
    mapValueClient.delete(mapKey(name, key))
  }


  def removeMapStorageFor(name: String) = {
    val keys: Set[Array[Byte]] = mapKeyClient.getValue(name, new HashSet[Array[Byte]](0))
    keys.foreach {
      mapValueClient.delete(mapKey(name, _))
    }
    mapKeyClient.delete(name)
  }

  def insertMapStorageEntryFor(name: String, key: Array[Byte], value: Array[Byte]) = {
    mapValueClient.put(mapKey(name, key))
    val keys: Set[Array[Byte]] = mapKeyClient.getValue(name, new HashSet[Array[Byte]](0))
    keys += key
    mapKeyClient.put(name, keys)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[(Array[Byte], Array[Byte])]) = {
    val newKeys = new HashSet[Array[Byte]]
    entries.foreach {
      (key, value) => mapValueClient.put(mapKey(name, key), value)
      newKeys += key
    }
    val keys: Set[Array[Byte]] = mapKeyClient.getValue(name, new HashSet[Array[Byte]](0))
    keys += key
    mapKeyClient.put(name, keys)
  }


  def getVectorStorageSizeFor(name: String): Int = null

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = null

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = null

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = null

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = null

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = null


}