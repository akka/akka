/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.serialization.Serializer
import se.scalablesolutions.akka.Config.config

import org.apache.cassandra.service._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object CassandraStorage extends MapStorage 
  with VectorStorage with RefStorage with Logging {
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
      case unknown => throw new IllegalArgumentException("Consistency level [" + unknown + "] is not supported. Expected one of [ZERO, ONE, QUORUM, ALL]")
    }
  }
  val IS_ASCENDING = true

  @volatile private[this] var isRunning = false
  private[this] val protocol: Protocol = Protocol.Binary
/*   {
     config.getString("akka.storage.cassandra.procotol", "binary") match {
      case "binary" => Protocol.Binary
      case "json" => Protocol.JSON
      case "simple-json" => Protocol.SimpleJSON
      case unknown => throw new UnsupportedOperationException("Unknown storage serialization protocol [" + unknown + "]")
    }
  }
*/

  private[this] val serializer: Serializer = {
    config.getString("akka.storage.cassandra.storage-format", "manual") match {
      case "scala-json" => Serializer.ScalaJSON
      case "java-json" =>  Serializer.JavaJSON
      case "protobuf" =>   Serializer.Protobuf
      case "java" =>       Serializer.Java
      case "manual" =>     Serializer.NOOP
      case "sbinary" =>    throw new UnsupportedOperationException("SBinary serialization protocol is not yet supported for storage")
      case "avro" =>       throw new UnsupportedOperationException("Avro serialization protocol is not yet supported for storage")
      case unknown =>      throw new UnsupportedOperationException("Unknown storage serialization protocol [" + unknown + "]")
    }
  }

  private[this] val sessions = new CassandraSessionPool(
    KEYSPACE,
    StackPool(SocketProvider(CASSANDRA_SERVER_HOSTNAME, CASSANDRA_SERVER_PORT)),
    protocol,
    CONSISTENCY_LEVEL)

  // ===============================================================
  // For Ref
  // ===============================================================

  def insertRefStorageFor(name: String, element: AnyRef) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(REF_COLUMN_PARENT.getColumn_family, null, REF_KEY),
        serializer.out(element),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getRefStorageFor(name: String): Option[AnyRef] = {
    try {
      val column: Option[ColumnOrSuperColumn] = sessions.withSession {
        _ | (name, new ColumnPath(REF_COLUMN_PARENT.getColumn_family, null, REF_KEY))
      }
      if (column.isDefined) Some(serializer.in(column.get.getColumn.value, None))
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

  def insertVectorStorageEntryFor(name: String, element: AnyRef) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(getVectorStorageSizeFor(name))),
        serializer.out(element),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  // FIXME implement insertVectorStorageEntriesFor
  def insertVectorStorageEntriesFor(name: String, elements: List[AnyRef]) = {
    throw new UnsupportedOperationException("insertVectorStorageEntriesFor for CassandraStorage is not implemented yet")
  }

  def updateVectorStorageEntryFor(name: String, index: Int, elem: AnyRef) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(index)),
        serializer.out(elem),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getVectorStorageEntryFor(name: String, index: Int): AnyRef =  {
    val column: Option[ColumnOrSuperColumn] = sessions.withSession {
      _ | (name, new ColumnPath(VECTOR_COLUMN_PARENT.getColumn_family, null, intToBytes(index)))
    }
    if (column.isDefined) serializer.in(column.get.column.value, None)
    else throw new NoSuchElementException("No element for vector [" + name + "] and index [" + index + "]")
  }

  def getVectorStorageRangeFor(name: String, start: Option[Int], finish: Option[Int], count: Int): List[AnyRef] = {
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
    columns.map(column => serializer.in(column.getColumn.value, None))
  }

  def getVectorStorageSizeFor(name: String): Int = {
    sessions.withSession {
      _ |# (name, VECTOR_COLUMN_PARENT)
    }
  }

  // ===============================================================
  // For Map
  // ===============================================================

  def insertMapStorageEntryFor(name: String, key: AnyRef, element: AnyRef) = {
    sessions.withSession {
      _ ++| (name,
        new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, serializer.out(key)),
        serializer.out(element),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[AnyRef, AnyRef]]) = {
    val batch = new scala.collection.mutable.HashMap[String, List[ColumnOrSuperColumn]]
    for (entry <- entries) {
      val columnOrSuperColumn = new ColumnOrSuperColumn
      columnOrSuperColumn.setColumn(new Column(serializer.out(entry._1), serializer.out(entry._2), System.currentTimeMillis))
      batch + (MAP_COLUMN_PARENT.getColumn_family -> List(columnOrSuperColumn))
    }
    sessions.withSession {
      _ ++| (name, batch, CONSISTENCY_LEVEL)
    }
  }

  def getMapStorageEntryFor(name: String, key: AnyRef): Option[AnyRef] = {
    try {
      val column: Option[ColumnOrSuperColumn] = sessions.withSession {
        _ | (name, new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, serializer.out(key)))
      }
      if (column.isDefined) Some(serializer.in(column.get.getColumn.value, None))
      else None
    } catch {
      case e =>
        log.error(e, "Could not retreive Map from storage")
        None
    }
  }

  def getMapStorageFor(name: String): List[Tuple2[AnyRef, AnyRef]]  = {
    val size = getMapStorageSizeFor(name)
    sessions.withSession { session =>
      val columns = session / (name, MAP_COLUMN_PARENT, EMPTY_BYTE_ARRAY, EMPTY_BYTE_ARRAY, true, size, CONSISTENCY_LEVEL)
      for {
        columnOrSuperColumn <- columns
        entry = (serializer.in(columnOrSuperColumn.column.name, None), serializer.in(columnOrSuperColumn.column.value, None))
      } yield entry
    }
  }

  def getMapStorageSizeFor(name: String): Int = sessions.withSession {
    _ |# (name, MAP_COLUMN_PARENT)
  }

  def removeMapStorageFor(name: String): Unit = removeMapStorageFor(name, null)

  def removeMapStorageFor(name: String, key: AnyRef): Unit = {
    val keyBytes = if (key == null) null else serializer.out(key)
    sessions.withSession {
      _ -- (name,
        new ColumnPath(MAP_COLUMN_PARENT.getColumn_family, null, keyBytes),
        System.currentTimeMillis,
        CONSISTENCY_LEVEL)
    }
  }

  def getMapStorageRangeFor(name: String, start: Option[AnyRef], finish: Option[AnyRef], count: Int):
  List[Tuple2[AnyRef, AnyRef]] = {
    val startBytes = if (start.isDefined) serializer.out(start.get) else null
    val finishBytes = if (finish.isDefined) serializer.out(finish.get) else null
    val columns: List[ColumnOrSuperColumn] = sessions.withSession {
      _ / (name, MAP_COLUMN_PARENT, startBytes, finishBytes, IS_ASCENDING, count, CONSISTENCY_LEVEL)
    }
    columns.map(column => (column.getColumn.name, serializer.in(column.getColumn.value, None)))
  }
}
