/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import java.io.File
import java.lang.reflect.Constructor

import org.apache.cassandra.config.DatabaseDescriptor
import org.apache.cassandra.service._

/**
 * NOTE: requires command line options:
 * <br/>
 *   <code>-Dcassandra -Dstorage-config=config/ -Dpidfile=akka.pid</code>
 * <p/>
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final object CassandraNode extends Logging {

  val TABLE_NAME = "akka"
  val MAP_COLUMN_FAMILY = "map"
  val VECTOR_COLUMN_FAMILY = "vector"
  val REF_COLUMN_FAMILY = "ref:item"

  // TODO: make pluggable (JSON, Thrift, Protobuf etc.)
  private[this] var serializer: Serializer = new JavaSerializationSerializer
  
  // TODO: is this server thread-safe or needed to be wrapped up in an actor?
  private[this] val server = classOf[CassandraServer].newInstance.asInstanceOf[CassandraServer]
  
  def start = {
    try {
      server.start
      log.info("Persistent storage has started up successfully");
    } catch {
      case e =>
        log.error("Could not start up persistent storage")
        throw e
    }
  }

  def stop = {}

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
      false) // FIXME: what is this flag for?
  }

  def getRefStorageFor(name: String): Option[AnyRef] = {
    try {
      val column = server.get_column(TABLE_NAME, name, REF_COLUMN_FAMILY)
      Some(serializer.in(column.value))
    } catch {
      case e =>
        e.printStackTrace
        None //throw new Predef.NoSuchElementException(e.getMessage)
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
      false) // FIXME: what is this flag for?
  }

  def getVectorStorageEntryFor(name: String, index: Int): AnyRef = {                                                                                
    try {
      val column = server.get_column(TABLE_NAME, name, VECTOR_COLUMN_FAMILY + ":" + index)
      serializer.in(column.value)
    } catch {
      case e => throw new Predef.NoSuchElementException(e.getMessage)
    }  
  }

  def getVectorStorageRangeFor(name: String, start: Int, count: Int): List[AnyRef]  =
    server.get_slice(TABLE_NAME, name, VECTOR_COLUMN_FAMILY, start, count)
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
      false) // FIXME: what is this flag for?
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
      false) // non-blocking
  }

  def getMapStorageEntryFor(name: String, key: AnyRef): Option[AnyRef] = {
    try {
      val column = server.get_column(TABLE_NAME, name, MAP_COLUMN_FAMILY + ":" + key)
      Some(serializer.in(column.value))
    } catch { case e => None }
  }

  def getMapStorageFor(name: String): List[Tuple2[String, AnyRef]]  = {
    val columns = server.get_columns_since(TABLE_NAME, name, MAP_COLUMN_FAMILY, -1)
      .toArray.toList.asInstanceOf[List[org.apache.cassandra.service.column_t]]
    for {
      column <- columns
      col = (column.columnName, serializer.in(column.value))
    } yield col
  }
  
  def getMapStorageSizeFor(name: String): Int =
    server.get_column_count(TABLE_NAME, name, MAP_COLUMN_FAMILY)

  def removeMapStorageFor(name: String) =
    server.remove(TABLE_NAME, name, MAP_COLUMN_FAMILY, System.currentTimeMillis, false)

  def getMapStorageRangeFor(name: String, start: Int, count: Int): List[Tuple2[String, AnyRef]] =
    server.get_slice(TABLE_NAME, name, MAP_COLUMN_FAMILY, start, count)
      .toArray.toList.asInstanceOf[List[Tuple2[String, AnyRef]]]
}

/*
 * This code is only for starting up the Cassandra Thrift server, perhaps later
  
import scala.actors.Actor._

import com.facebook.thrift.protocol.TBinaryProtocol
import com.facebook.thrift.protocol.TProtocolFactory
import com.facebook.thrift.server.TThreadPoolServer
import com.facebook.thrift.transport.TServerSocket
import com.facebook.thrift.transport.TTransportException
import com.facebook.thrift.transport.TTransportFactory
import com.facebook.thrift.TProcessorFactory

  private[this] val serverEngine: TThreadPoolServer = try {
    val pidFile = System.getProperty("pidfile")
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
      log.error("Could not start up persistent storage node.")
      throw e
  }
  private[this] val serverDaemon = actor {
    receive {
     case Start => 
        log.info("Persistent storage node starting up...")
        serverEngine.serve
      case Stop =>      
        log.info("Persistent storage node shutting down...")
        serverEngine.stop
      //case Insert(..) => 
      //  server.
    }
  }
*/

