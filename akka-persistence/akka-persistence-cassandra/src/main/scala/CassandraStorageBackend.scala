/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.cassandra

import se.scalablesolutions.akka.stm._
import se.scalablesolutions.akka.persistence.common._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.config.Config.config

import org.apache.cassandra.thrift._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object CassandraStorageBackend extends
  MapStorageBackend[Array[Byte], Array[Byte]] with
  VectorStorageBackend[Array[Byte]] with
  RefStorageBackend[Array[Byte]] with
  Logging {

  type ElementType = Array[Byte]

  val KEYSPACE             = "akka"
  val MAP_COLUMN_PARENT    = new ColumnParent("map")
  val VECTOR_COLUMN_PARENT = new ColumnParent("vector")
  val REF_COLUMN_PARENT    = new ColumnParent("ref")
  val REF_KEY              = "item".getBytes("UTF-8")
  val EMPTY_BYTE_ARRAY     = new Array[Byte](0)

  val CASSANDRA_SERVER_HOSTNAME = config.getString("akka.storage.cassandra.hostname", "127.0.0.1")
  val CASSANDRA_SERVER_PORT     = config.getInt("akka.storage.cassandra.port", 9160)
  val CONSISTENCY_LEVEL = {
    config.getString("akka.storage.cassandra.consistency-level", "QUORUM") match {
      case "ZERO"         => ConsistencyLevel.ZERO
      case "ONE"          => ConsistencyLevel.ONE
      case "QUORUM"       => ConsistencyLevel.QUORUM
      case "DCQUORUM"     => ConsistencyLevel.DCQUORUM
      case "DCQUORUMSYNC" => ConsistencyLevel.DCQUORUMSYNC
      case "ALL"          => ConsistencyLevel.ALL
      case "ANY"          => ConsistencyLevel.ANY
      case unknown        => throw new IllegalArgumentException(
        "Cassandra consistency level [" + unknown + "] is not supported." +
        "\n\tExpected one of [ZERO, ONE, QUORUM, DCQUORUM, DCQUORUMSYNC, ALL, ANY] in the akka.conf configuration file.")
    }
  }
  val IS_ASCENDING = true

  @volatile private[this] var isRunning = false
  private[this] val protocol: Protocol = Protocol.Binary

  private[this] val sessions = new CassandraSessionPool(
    KEYSPACE,
    StackPool(SocketProvider(CASSANDRA_SERVER_HOSTNAME, CASSANDRA_SERVER_PORT)),
    protocol,
    CONSISTENCY_LEVEL)

  // ===============================================================
  // For Ref
  // ===============================================================

  def insertRefStorageFor(name: String, element: Array[Byte]) = {
    val columnPath = new ColumnPath(REF_COLUMN_PARENT.getColumn_family)
    columnPath.setColumn(REF_KEY)
    sessions.withSession {
      _ ++| (name,
        columnPath,
        element,
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    val columnPath = new ColumnPath(REF_COLUMN_PARENT.getColumn_family)
    columnPath.setColumn(REF_KEY)
    try {
      val column: Option[ColumnOrSuperColumn] = sessions.withSession {
        _ | (name, columnPath)
      }
      if (column.isDefined) Some(column.get.getColumn.value)
      else None
    } catch {
      case e =>
        log.info("Could not retreive Ref from storage")
        None
    }
  }

  // ===============================================================
  // For Vector
  // ===============================================================

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    val columnPath = new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family)
    columnPath.setColumn(intToBytes(getVectorStorageSizeFor(name)))
    sessions.withSession {
      _ ++| (name,
        columnPath,
        element,
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) =
    elements.foreach(insertVectorStorageEntryFor(name, _))

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {
    val columnPath = new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family)
    columnPath.setColumn(intToBytes(index))
    sessions.withSession {
      _ ++| (name,
        columnPath,
        elem,
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] =  {
    val columnPath = new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family)
    columnPath.setColumn(intToBytes(index))
    val column: Option[ColumnOrSuperColumn] = sessions.withSession {
      _ | (name, columnPath)
    }
    if (column.isDefined) column.get.column.value
    else throw new NoSuchElementException("No element for vector [" + name + "] and index [" + index + "]")
  }

  /**
   * if <tt>start</tt> and <tt>finish</tt> both are defined, ignore <tt>count</tt> and
   * report the range [start, finish)
   * if <tt>start</tt> is not defined, assume <tt>start</tt> = 0
   * if <tt>start</tt> == 0 and <tt>finish</tt> == 0, return an empty collection
   */
  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int):
    List[Array[Byte]] = {
    val startBytes = if (start.isDefined) intToBytes(start.get) else null
    val finishBytes = if (finish.isDefined) intToBytes(finish.get) else null
    val columns: List[ColumnOrSuperColumn] = sessions.withSession {
      _ / (name,
        VECTOR_COLUMN_PARENT,
        startBytes, finishBytes,
        IS_ASCENDING,
        count,
        CONSISTENCY_LEVEL)
    }
    columns.map(column => column.getColumn.value)
  }

  def getVectorStorageSizeFor(name: String): Int = {
    sessions.withSession {
      _ |# (name, VECTOR_COLUMN_PARENT)
    }
  }

  // ===============================================================
  // For Map
  // ===============================================================

  def insertMapStorageEntryFor(name: String, key: Array[Byte], element: Array[Byte]) = {
    val columnPath = new ColumnPath(MAP_COLUMN_PARENT.getColumn_family)
    columnPath.setColumn(key)
    sessions.withSession {
      _ ++| (name,
        columnPath,
        element,
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[Array[Byte], Array[Byte]]]) = {
    val batch = new scala.collection.mutable.HashMap[String, List[ColumnOrSuperColumn]]
    for (entry <- entries) {
      val columnOrSuperColumn = new ColumnOrSuperColumn
      columnOrSuperColumn.setColumn(new Column(entry._1, entry._2, System.currentTimeMillis))
      batch += (MAP_COLUMN_PARENT.getColumn_family -> List(columnOrSuperColumn))
    }
    sessions.withSession {
      _ ++| (name, batch, CONSISTENCY_LEVEL)
    }
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    try {
      val columnPath = new ColumnPath(MAP_COLUMN_PARENT.getColumn_family)
      columnPath.setColumn(key)
      val column: Option[ColumnOrSuperColumn] = sessions.withSession {
        _ | (name, columnPath)
      }
      if (column.isDefined) Some(column.get.getColumn.value)
      else None
    } catch {
      case e =>
        log.info("Could not retreive Map from storage")
        None
    }
  }

  def getMapStorageFor(name: String): List[Tuple2[Array[Byte], Array[Byte]]]  = {
    val size = getMapStorageSizeFor(name)
    sessions.withSession { session =>
      val columns = session /
          (name, MAP_COLUMN_PARENT,
           EMPTY_BYTE_ARRAY, EMPTY_BYTE_ARRAY,
           true, size, CONSISTENCY_LEVEL)
      for {
        columnOrSuperColumn <- columns
        entry = (columnOrSuperColumn.column.name, columnOrSuperColumn.column.value)
      } yield entry
    }
  }

  def getMapStorageSizeFor(name: String): Int = sessions.withSession {
    _ |# (name, MAP_COLUMN_PARENT)
  }

  def removeMapStorageFor(name: String): Unit = removeMapStorageFor(name, null)

  def removeMapStorageFor(name: String, key: Array[Byte]): Unit = {
    val keyBytes = if (key eq null) null else key
    val columnPath = new ColumnPath(MAP_COLUMN_PARENT.getColumn_family)
    columnPath.setColumn(keyBytes)
    sessions.withSession {
      _ -- (name,
        columnPath,
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getMapStorageRangeFor(name: String, start: Option[Array[Byte]], finish: Option[Array[Byte]], count: Int):
    List[Tuple2[Array[Byte], Array[Byte]]] = {
    val startBytes = if (start.isDefined) start.get else null
    val finishBytes = if (finish.isDefined) finish.get else null
    val columns: List[ColumnOrSuperColumn] = sessions.withSession {
      _ / (name, MAP_COLUMN_PARENT, startBytes, finishBytes, IS_ASCENDING, count, CONSISTENCY_LEVEL)
    }
    columns.map(column => (column.getColumn.name, column.getColumn.value))
  }
}
