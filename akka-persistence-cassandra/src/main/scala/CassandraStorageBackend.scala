/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.Config.config

import org.apache.cassandra.service._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[akka] object CassandraStorageBackend extends
  MapStorageBackend[Array[Byte], Array[Byte]] with
  VectorStorageBackend[Array[Byte]] with
  RefStorageBackend[Array[Byte]] with
  Logging {

  type ElementType = Array[Byte]

  val KEYSPACE = "akka"
  val MAP_COLUMN_PARENT = new ColumnParent("map", null)
  val VECTOR_COLUMN_PARENT = new ColumnParent("vector", null)
  val REF_COLUMN_PARENT = new ColumnParent("ref", null)
  val REF_KEY = "item".getBytes("UTF-8")
  val EMPTY_BYTE_ARRAY = new Array[Byte](0)

  val CASSANDRA_SERVER_HOSTNAME = config.getString("akka.storage.cassandra.hostname", "127.0.0.1")
  val CASSANDRA_SERVER_PORT = config.getInt("akka.storage.cassandra.port", 9160)
  val CONSISTENCY_LEVEL = {
    config.getString("akka.storage.cassandra.consistency-level", "QUORUM") match {
      case "ZERO" =>   0
      case "ONE" =>    1
      case "QUORUM" => 2
      case "ALL" =>    3
      case unknown => throw new IllegalArgumentException(
        "Cassandra consistency level [" + unknown + "] is not supported. Expected one of [ZERO, ONE, QUORUM, ALL]")
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
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(REF_COLUMN_PARENT.getColumn_family, null, REF_KEY),
        element,
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getRefStorageFor(name: String): Option[Array[Byte]] = {
    try {
      val column: Option[ColumnOrSuperColumn] = sessions.withSession {
        _ | (name, new ColumnPath(REF_COLUMN_PARENT.getColumn_family, null, REF_KEY))
      }
      if (column.isDefined) Some(column.get.getColumn.value)
      else None
    } catch {
      case e =>
        log.error(e, "Could not retreive Ref from storage")
        None
    }
  }

  // ===============================================================
  // For Vector
  // ===============================================================

  def insertVectorStorageEntryFor(name: String, element: Array[Byte]) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(getVectorStorageSizeFor(name))),
        element,
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def insertVectorStorageEntriesFor(name: String, elements: List[Array[Byte]]) = {
    throw new UnsupportedOperationException("CassandraStorageBackend::insertVectorStorageEntriesFor is not implemented")
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: Array[Byte]) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(index)),
        elem,
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getVectorStorageEntryFor(name: String, index: Int): Array[Byte] =  {
    val column: Option[ColumnOrSuperColumn] = sessions.withSession {
      _ | (name, new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(index)))
    }
    if (column.isDefined) column.get.column.value
    else throw new NoSuchElementException("No element for vector [" + name + "] and index [" + index + "]")
  }

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[Array[Byte]] = {
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
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, key),
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
      batch + (MAP_COLUMN_PARENT.getColumn_family -> List(columnOrSuperColumn))
    }
    sessions.withSession {
      _ ++| (name, batch, CONSISTENCY_LEVEL)
    }
  }

  def getMapStorageEntryFor(name: String, key: Array[Byte]): Option[Array[Byte]] = {
    try {
      val column: Option[ColumnOrSuperColumn] = sessions.withSession {
        _ | (name, new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, key))
      }
      if (column.isDefined) Some(column.get.getColumn.value)
      else None
    } catch {
      case e =>
        log.error(e, "Could not retreive Map from storage")
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
    sessions.withSession {
      _ -- (name,
        new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, keyBytes),
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
