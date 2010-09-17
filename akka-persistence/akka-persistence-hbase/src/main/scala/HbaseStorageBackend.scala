/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.hbase

import scala.collection.mutable.ListBuffer
import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.config.Config.config
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.HColumnDescriptor
import org.apache.hadoop.hbase.HTableDescriptor
import org.apache.hadoop.hbase.client.HBaseAdmin
import org.apache.hadoop.hbase.client.HTable
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.client.Get
import org.apache.hadoop.hbase.client.Delete
import org.apache.hadoop.hbase.util.Bytes

/**
 * @author <a href="http://www.davidgreco.it">David Greco</a>
 */
private[akka] object HbaseStorageBackend extends MapStorageBackend[Array[Byte], Array[Byte]] with VectorStorageBackend[Array[Byte]] with RefStorageBackend[Array[Byte]] with Logging {

  val EMPTY_BYTE_ARRAY = new Array[Byte](0)
  val HBASE_ZOOKEEPER_QUORUM = config.getString("akka.storage.hbase.zookeeper.quorum", "localhost")
  val CONFIGURATION = new HBaseConfiguration
  val ADMIN = new HBaseAdmin(CONFIGURATION)
  val REF_TABLE_NAME = "__REF_TABLE"
  val VECTOR_TABLE_NAME = "__VECTOR_TABLE"
  val VECTOR_ELEMENT_COLUMN_FAMILY_NAME = "__VECTOR_ELEMENT"
  val MAP_ELEMENT_COLUMN_FAMILY_NAME = "__MAP_ELEMENT"
  val MAP_TABLE_NAME = "__MAP_TABLE"
  var REF_TABLE: HTable = _
  var VECTOR_TABLE: HTable = _
  var MAP_TABLE: HTable = _

  CONFIGURATION.set("hbase.zookeeper.quorum", HBASE_ZOOKEEPER_QUORUM)

  init
  
  def init {
    if (!ADMIN.tableExists(REF_TABLE_NAME)) {
      ADMIN.createTable(new HTableDescriptor(REF_TABLE_NAME))
      ADMIN.disableTable(REF_TABLE_NAME)
      ADMIN.addColumn(REF_TABLE_NAME, new HColumnDescriptor("element"))
      ADMIN.enableTable(REF_TABLE_NAME)
    }
    REF_TABLE = new HTable(CONFIGURATION, REF_TABLE_NAME);
    
    if (!ADMIN.tableExists(VECTOR_TABLE_NAME)) {
      ADMIN.createTable(new HTableDescriptor(VECTOR_TABLE_NAME))
      ADMIN.disableTable(VECTOR_TABLE_NAME)
      ADMIN.addColumn(VECTOR_TABLE_NAME, new HColumnDescriptor(VECTOR_ELEMENT_COLUMN_FAMILY_NAME))
      ADMIN.enableTable(VECTOR_TABLE_NAME);
    }
    VECTOR_TABLE = new HTable(CONFIGURATION, VECTOR_TABLE_NAME) 

    if (!ADMIN.tableExists(MAP_TABLE_NAME)) {
      ADMIN.createTable(new HTableDescriptor(MAP_TABLE_NAME))
      ADMIN.disableTable(MAP_TABLE_NAME)
      ADMIN.addColumn(MAP_TABLE_NAME, new HColumnDescriptor(MAP_ELEMENT_COLUMN_FAMILY_NAME))
      ADMIN.enableTable(MAP_TABLE_NAME);
    }
    MAP_TABLE = new HTable(CONFIGURATION, MAP_TABLE_NAME) 
  }
  
  def drop {
    if (ADMIN.tableExists(REF_TABLE_NAME)) {
      ADMIN.disableTable(REF_TABLE_NAME)
      ADMIN.deleteTable(REF_TABLE_NAME)
    }
    if (ADMIN.tableExists(VECTOR_TABLE_NAME)) {
      ADMIN.disableTable(VECTOR_TABLE_NAME)
      ADMIN.deleteTable(VECTOR_TABLE_NAME)
    }
    if (ADMIN.tableExists(MAP_TABLE_NAME)) {
      ADMIN.disableTable(MAP_TABLE_NAME)
      ADMIN.deleteTable(MAP_TABLE_NAME)
    }    
    init
  }
  
  // ===============================================================
  // For Ref
  // ===============================================================

  def insertRefStorageFor(name: String, element: Array[Byte]) = {
    val row = new Put(Bytes.toBytes(name))
    row.add(Bytes.toBytes("element"), Bytes.toBytes("element"), element)
    REF_TABLE.put(row)
  }

  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    val row = new Get(Bytes.toBytes(name))
    val result = REF_TABLE.get(row)
    if (result.isEmpty()) {
      return None;
    } else {
      val element = result.getValue(Bytes.toBytes("element"), Bytes.toBytes("element"))
      return Some(element)
    }
  }

  // ===============================================================
  // For Vector
  // ===============================================================

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    val row  = new Put(Bytes.toBytes(name))
    val size = getVectorStorageSizeFor(name)
    row.add(Bytes.toBytes(VECTOR_ELEMENT_COLUMN_FAMILY_NAME), Bytes.toBytes(size), element)
    VECTOR_TABLE.put(row)
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = elements.reverse.foreach(insertVectorStorageEntryFor(name, _))

  def updateVectorStorageEntryFor(name: String, index: Int, element: Array[Byte]) = {
    val row = new Put(Bytes.toBytes(name))
    row.add(Bytes.toBytes(VECTOR_ELEMENT_COLUMN_FAMILY_NAME), Bytes.toBytes(index), element)
    VECTOR_TABLE.put(row)
  }

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] = {
    val row = new Get(Bytes.toBytes(name))
    val result = VECTOR_TABLE.get(row)
    val size   = result.size
    val colnum = size - index - 1
    return result.getValue(Bytes.toBytes(VECTOR_ELEMENT_COLUMN_FAMILY_NAME),Bytes.toBytes(colnum))
  }

  /**
   * if <tt>start</tt> and <tt>finish</tt> both are defined, ignore <tt>count</tt> and
   * report the range [start, finish)
   * if <tt>start</tt> is not defined, assume <tt>start</tt> = 0
   * if <tt>start</tt> == 0 and <tt>finish</tt> == 0, return an empty collection
   */
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
    val row = new Get(Bytes.toBytes(name))
    val result = VECTOR_TABLE.get(row)
    val size = result.size
    var listBuffer = new ListBuffer[Array[Byte]]

    if(start.isDefined && finish.isDefined) {
      for(i <- start.get to finish.get-1) {
	val colnum = size - i - 1
	listBuffer += result.getValue(Bytes.toBytes(VECTOR_ELEMENT_COLUMN_FAMILY_NAME),Bytes.toBytes(colnum))
      }
      return listBuffer.toList
    } else {
      val b = start.getOrElse(0)
      val e = if(!finish.isDefined) {
	val ee: Int = b + count -1
	if(ee < size-1) ee else size-1
      }
      for(i <- b.asInstanceOf[Int] to e.asInstanceOf[Int]) {
	val colnum = size - i - 1
	listBuffer += result.getValue(Bytes.toBytes(VECTOR_ELEMENT_COLUMN_FAMILY_NAME),Bytes.toBytes(colnum))
      }
      return listBuffer.toList
    }
  }

  def getVectorStorageSizeFor(name: String): Int = {
    val row = new Get(Bytes.toBytes(name))
    val result = VECTOR_TABLE.get(row)
    if (result.isEmpty) {
      0
    } else {
      result.size
    }
  }

  // ===============================================================
  // For Map
  // ===============================================================

  def insertMapStorageEntryFor(name: String, key: Array[Byte], element: Array[Byte]) = {
    val row = new Put(Bytes.toBytes(name))
    row.add(Bytes.toBytes(MAP_ELEMENT_COLUMN_FAMILY_NAME), key, element)
    MAP_TABLE.put(row)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[Array[Byte], Array[Byte]]]) = entries.foreach((x:Tuple2[Array[Byte], Array[Byte]]) => insertMapStorageEntryFor(name, x._1, x._2))

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    val row = new Get(Bytes.toBytes(name))
    val result = MAP_TABLE.get(row)
    val value = result.getValue(Bytes.toBytes(MAP_ELEMENT_COLUMN_FAMILY_NAME), key)
    if(value == null)
      None
    else
      Some(value)
  }

  def getMapStorageFor(name: String): List[Tuple2[Array[Byte], Array[Byte]]] = {
    val row = new Get(Bytes.toBytes(name))
    val result = MAP_TABLE.get(row)
    val raw = result.getFamilyMap(Bytes.toBytes(MAP_ELEMENT_COLUMN_FAMILY_NAME)).entrySet.toArray
    val listBuffer = new ListBuffer[Tuple2[Array[Byte], Array[Byte]]]

    for(i <- Range(raw.size-1, -1, -1)) {
      listBuffer += Tuple2(raw.apply(i).asInstanceOf[java.util.Map.Entry[Array[Byte], Array[Byte]]].getKey, raw.apply(i).asInstanceOf[java.util.Map.Entry[Array[Byte],Array[Byte]]].getValue)
    }
    listBuffer.toList
  }

  def getMapStorageSizeFor(name: String): Int = {
    val row = new Get(Bytes.toBytes(name))
    val result = MAP_TABLE.get(row)
    if (result.isEmpty) {
      0
    } else {
      result.size
    }
  }

  def removeMapStorageFor(name: String): Unit = {
    val row = new Delete(Bytes.toBytes(name))
    MAP_TABLE.delete(row)
  }

  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = {
    val row = new Delete(Bytes.toBytes(name))
    row.deleteColumns(Bytes.toBytes(MAP_ELEMENT_COLUMN_FAMILY_NAME), key)
    MAP_TABLE.delete(row)
  }

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int): List[Tuple2[Array[Byte], Array[Byte]]] = {
    Nil
  }
}
