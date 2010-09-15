/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.hbase

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.config.Config.config
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.HBaseAdmin
import org.apache.hadoop.hbase.client.HTable

/**
 * @author <a href="http://www.davidgreco.it">David Greco</a>
 */
private[akka] object HbaseStorageBackend extends MapStorageBackend[Array[Byte], Array[Byte]] with VectorStorageBackend[Array[Byte]] with RefStorageBackend[Array[Byte]] with Logging {

  type ElementType = Array[Byte]

  val KEYSPACE = "akka"
  val REF_KEY = "item".getBytes("UTF-8")
  val EMPTY_BYTE_ARRAY = new Array[Byte](0)

  val HBASE_ZOOKEEPER_QUORUM = config.getString("akka.storage.hbase.zookeeper.quorum", "localhost")

  val CONFIGURATION = new HBaseConfiguration
  CONFIGURATION.set("hbase.zookeeper.quorum", HBASE_ZOOKEEPER_QUORUM)
  val ADMIN = new HBaseAdmin(CONFIGURATION)

  // ===============================================================
  // For Ref
  // ===============================================================

  def insertRefStorageFor(name: String, element: Array[Byte]) = {}

  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    return None
  }

  // ===============================================================
  // For Vector
  // ===============================================================

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {}

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = elements.foreach(insertVectorStorageEntryFor(name, _))

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {}

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = {
    EMPTY_BYTE_ARRAY
  }

  /**
   * if <tt>start</tt> and <tt>finish</tt> both are defined, ignore <tt>count</tt> and
   * report the range [start, finish)
   * if <tt>start</tt> is not defined, assume <tt>start</tt> = 0
   * if <tt>start</tt> == 0 and <tt>finish</tt> == 0, return an empty collection
   */
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    Nil
  }

  def getVectorStorageSizeFor(name: String): Int = {
    0
  }

  // ===============================================================
  // For Map
  // ===============================================================

  def insertMapStorageEntryFor(name: String, key: Array[Byte], element: Array[Byte]) = {}

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[Array[Byte], Array[Byte]]]) = {}

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    None
  }

  def getMapStorageFor(name: String): List[Tuple2[Array[Byte], Array[Byte]]] = {
    Nil
  }

  def getMapStorageSizeFor(name: String): Int = {
    0
  }

  def removeMapStorageFor(name: String): Unit = removeMapStorageFor(name, null)

  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = {}

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int): List[Tuple2[Array[Byte], Array[Byte]]] = {
    Nil
  }
}
