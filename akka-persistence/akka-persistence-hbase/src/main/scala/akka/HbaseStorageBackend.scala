/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.hbase

import scala.collection.mutable.ListBuffer
import akka.stm._
import akka.persistence.common._
import akka.util.Logging
import akka.util.Helpers._
import akka.config.Config.config
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

  val HBASE_ZOOKEEPER_QUORUM = config.getString("akka.storage.hbase.zookeeper-quorum", "localhost")
  val CONFIGURATION = new HBaseConfiguration
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
    val ADMIN = new HBaseAdmin(CONFIGURATION)

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
    val ADMIN = new HBaseAdmin(CONFIGURATION)

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

    if (result.isEmpty())
      None
    else
      Some(result.getValue(Bytes.toBytes("element"), Bytes.toBytes("element")))
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

    result.getValue(Bytes.toBytes(VECTOR_ELEMENT_COLUMN_FAMILY_NAME),Bytes.toBytes(colnum))
  }

  /**
   * if <tt>start</tt> and <tt>finish</tt> both are defined, ignore <tt>count</tt> and
   * report the range [start, finish)
   * if <tt>start</tt> is not defined, assume <tt>start</tt> = 0
   * if <tt>start</tt> == 0 and <tt>finish</tt> == 0, return an empty collection
   */
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {

    import scala.math._

    val row = new Get(Bytes.toBytes(name))
    val result = VECTOR_TABLE.get(row)
    val size = result.size
    var listBuffer = new ListBuffer[Array[Byte]]
    var b = 0
    var e = 0

    if(start.isDefined && finish.isDefined) {
      b = start.get
      e = finish.get - 1
    } else {
      b = start.getOrElse(0)
      e = finish.getOrElse(min(b + count - 1, size - 1))
    }
    for(i <- b to e) {
      val colnum = size - i - 1
      listBuffer += result.getValue(Bytes.toBytes(VECTOR_ELEMENT_COLUMN_FAMILY_NAME),Bytes.toBytes(colnum))
    }
    listBuffer.toList
  }

  def getVectorStorageSizeFor(name: String): Int = {
    val row = new Get(Bytes.toBytes(name))
    val result = VECTOR_TABLE.get(row)

    if (result.isEmpty)
      0
    else
      result.size
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

    Option(result.getValue(Bytes.toBytes(MAP_ELEMENT_COLUMN_FAMILY_NAME), key))
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

    if (result.isEmpty)
      0
    else
      result.size
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
    val row = new Get(Bytes.toBytes(name))
    val result = MAP_TABLE.get(row)
    val map = result.getFamilyMap(Bytes.toBytes(MAP_ELEMENT_COLUMN_FAMILY_NAME))

    val startBytes = if (start.isDefined) start.get else map.firstEntry.getKey
    val finishBytes = if (finish.isDefined) finish.get else map.lastEntry.getKey
    val submap = map.subMap(startBytes, true, finishBytes, true)

    val iterator = submap.entrySet.iterator
    val listBuffer = new ListBuffer[Tuple2[Array[Byte], Array[Byte]]]
    val size = submap.size

    val cnt = if(count > size) size else count
    var i: Int = 0
    while(iterator.hasNext && i < cnt) {
      iterator.next match {
        case entry: java.util.Map.Entry[Array[Byte], Array[Byte]] => listBuffer += ((entry.getKey,entry.getValue))
        case _ =>
      }
      i = i+1
    }
    listBuffer.toList
  }
}
