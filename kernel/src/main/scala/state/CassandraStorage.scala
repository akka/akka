/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.state

import java.io.File

import kernel.util.Logging
import serialization.{Serializer, Serializable, SerializationProtocol}

import org.apache.cassandra.config.DatabaseDescriptor
import org.apache.cassandra.service._

import org.apache.thrift.server.TThreadPoolServer
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TServerSocket
import org.apache.thrift.transport.TTransportFactory
import org.apache.thrift.TProcessorFactory

/**
 * NOTE: requires command line options:
 * <br/>
 *   <code>-Dcassandra -Dstorage-config=config/ -Dpidfile=akka.pid</code>
 * <p/>
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object CassandraStorage extends Logging {
  val TABLE_NAME = "akka"
  val MAP_COLUMN_FAMILY = "map"
  val VECTOR_COLUMN_FAMILY = "vector"
  val REF_COLUMN_FAMILY = "ref:item"

  val IS_ASCENDING = true

  val RUN_THRIFT_SERVICE = kernel.Kernel.config.getBool("akka.storage.cassandra.thrift-server.service", false)
  val BLOCKING_CALL = {
     if (kernel.Kernel.config.getBool("akka.storage.cassandra.blocking", true)) 0
     else 1 
  }

  @volatile private[this] var isRunning = false
  private[this] val serializer: Serializer = {
    kernel.Kernel.config.getString("akka.storage.cassandra.storage-format", "java") match {
      case "scala-json" => Serializer.ScalaJSON
      case "java-json" =>  Serializer.JavaJSON
      //case "sbinary" =>    Serializer.SBinary
      case "java" =>       Serializer.Java
      case "avro" =>       throw new UnsupportedOperationException("Avro serialization protocol is not yet supported")
      case unknown =>      throw new UnsupportedOperationException("Unknown storage serialization protocol [" + unknown + "]")
    }
  }
 
  // TODO: is this server thread-safe or needed to be wrapped up in an actor?
  private[this] val server = classOf[CassandraServer].newInstance.asInstanceOf[CassandraServer]

  private[this] var thriftServer: CassandraThriftServer = _
  
  def start = synchronized {
    if (!isRunning) {
      try {
        server.start
        log.info("Cassandra persistent storage has started up successfully");
      } catch {
        case e =>
          log.error("Could not start up Cassandra persistent storage")
          throw e
      }
      if (RUN_THRIFT_SERVICE) {
        thriftServer = new CassandraThriftServer(server)
        thriftServer.start
      }
      isRunning
    }
  }

  def stop = if (isRunning) {
    //server.storageService.shutdown
    if (RUN_THRIFT_SERVICE) thriftServer.stop
  }

  // ===============================================================
  // For Ref
  // ===============================================================

  def insertRefStorageFor(name: String, element: AnyRef) = {
    server.insert(
      TABLE_NAME,
      name,
      REF_COLUMN_FAMILY,
      serializer.out(element),
      System.currentTimeMillis,
      BLOCKING_CALL)
  }

  def getRefStorageFor(name: String): Option[AnyRef] = {
    try {
      val column = server.get_column(TABLE_NAME, name, REF_COLUMN_FAMILY)
      Some(serializer.in(column.value, None))
    } catch {
      case e =>
        e.printStackTrace
        None 
    }
  }

  // ===============================================================
  // For Vector
  // ===============================================================

  def insertVectorStorageEntryFor(name: String, element: AnyRef) = {
    server.insert(
      TABLE_NAME,
      name,
      VECTOR_COLUMN_FAMILY + ":" + getVectorStorageSizeFor(name),
      serializer.out(element),
      System.currentTimeMillis,
      BLOCKING_CALL)
  }

  def getVectorStorageEntryFor(name: String, index: Int): AnyRef = {                                                                                
    try {
      val column = server.get_column(TABLE_NAME, name, VECTOR_COLUMN_FAMILY + ":" + index)
      serializer.in(column.value, None)
    } catch {
      case e =>
        e.printStackTrace
        throw new Predef.NoSuchElementException(e.getMessage)
    }  
  }

  def getVectorStorageRangeFor(name: String, start: Int, count: Int): List[AnyRef]  =
    server.get_slice(TABLE_NAME, name, VECTOR_COLUMN_FAMILY, IS_ASCENDING, count)
      .toArray.toList.asInstanceOf[List[Tuple2[String, AnyRef]]].map(tuple => tuple._2)

  def getVectorStorageSizeFor(name: String): Int =
    server.get_column_count(TABLE_NAME, name, VECTOR_COLUMN_FAMILY)

  // ===============================================================
  // For Map
  // ===============================================================

  def insertMapStorageEntryFor(name: String, key: String, value: AnyRef) = {
    server.insert(
      TABLE_NAME, 
      name,
      MAP_COLUMN_FAMILY + ":" + key,
      serializer.out(value),
      System.currentTimeMillis,
      BLOCKING_CALL)
  }

  def insertMapStorageEntriesFor(name: String, entries: List[Tuple2[String, AnyRef]]) = {
    import java.util.{Map, HashMap, List, ArrayList}
    val columns: Map[String, List[column_t]] = new HashMap
    for (entry <- entries) {
      val cls: List[column_t] = new ArrayList
      cls.add(new column_t(entry._1, serializer.out(entry._2), System.currentTimeMillis))
      columns.put(MAP_COLUMN_FAMILY, cls)
    }
    server.batch_insert(new batch_mutation_t(
      TABLE_NAME, 
      name,
      columns),
      BLOCKING_CALL)
  }

  def getMapStorageEntryFor(name: String, key: AnyRef): Option[AnyRef] = {
    try {
      val column = server.get_column(TABLE_NAME, name, MAP_COLUMN_FAMILY + ":" + key)
      Some(serializer.in(column.value, None))
    } catch {
      case e =>
        e.printStackTrace
        None
    }
  }

  def getMapStorageFor(name: String): List[Tuple2[String, AnyRef]]  = {
    val columns = server.get_columns_since(TABLE_NAME, name, MAP_COLUMN_FAMILY, -1)
      .toArray.toList.asInstanceOf[List[org.apache.cassandra.service.column_t]]
    for {
      column <- columns
      col = (column.columnName, serializer.in(column.value, None))
    } yield col
  }
  
  def getMapStorageSizeFor(name: String): Int =
    server.get_column_count(TABLE_NAME, name, MAP_COLUMN_FAMILY)

  def removeMapStorageFor(name: String) =
    server.remove(TABLE_NAME, name, MAP_COLUMN_FAMILY, System.currentTimeMillis, BLOCKING_CALL)

  def getMapStorageRangeFor(name: String, start: Int, count: Int): List[Tuple2[String, AnyRef]] = {
    server.get_slice(TABLE_NAME, name, MAP_COLUMN_FAMILY, IS_ASCENDING, count)
            .toArray.toList.asInstanceOf[List[Tuple2[String, AnyRef]]]
  }
}

class CassandraThriftServer(server: CassandraServer) extends Logging {
  case object Start
  case object Stop

  private[this] val serverEngine: TThreadPoolServer = try {
    val pidFile = kernel.Kernel.config.getString("akka.storage.cassandra.thrift-server.pidfile", "akka.pid")
    if (pidFile != null) new File(pidFile).deleteOnExit();
    val listenPort = DatabaseDescriptor.getThriftPort

    val processor = new Cassandra.Processor(server)
    val tServerSocket = new TServerSocket(listenPort)
    val tProtocolFactory = new TBinaryProtocol.Factory

    val options = new TThreadPoolServer.Options
    options.minWorkerThreads = 64
    new TThreadPoolServer(new TProcessorFactory(processor),
      tServerSocket,
      new TTransportFactory,
      new TTransportFactory,
      tProtocolFactory,
      tProtocolFactory,
      options)
  } catch {
    case e =>
      log.error("Could not start up Cassandra thrift service")
      throw e
  }

  import scala.actors.Actor._
  private[this] val serverDaemon = actor {
    receive {
     case Start =>
        serverEngine.serve
        log.info("Cassandra thrift service has starting up successfully")
     case Stop =>
        log.info("Cassandra thrift service is shutting down...")
        serverEngine.stop
    }
  }

  def start = serverDaemon ! Start
  def stop = serverDaemon ! Stop
}
