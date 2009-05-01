/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import java.io.File
import java.lang.reflect.Constructor

import org.apache.cassandra.config.DatabaseDescriptor
import org.apache.cassandra.service._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final object CassandraNode extends Logging {

  val TABLE_NAME = "akka"
  val ACTOR_KEY_PREFIX = "actor"
  val ACTOR_MAP_COLUMN_FAMILY = "map"
  
  // TODO: make pluggable (JSON, Thrift, Protobuf etc.)
  private[this] var serializer: Serializer = new JavaSerializationSerializer
  
  // TODO: is this server thread-safe or needed to be wrapped up in an actor?
  private[this] val server = {
    val ctor = classOf[CassandraServer].getConstructor(Array[Class[_]]():_*)
    ctor.setAccessible(true)
    ctor.newInstance(Array[AnyRef]():_*).asInstanceOf[CassandraServer]
  }
  
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

  def insertActorStorageEntry(actorName: String, entry: String, content: AnyRef) = {
    server.insert(
      TABLE_NAME, 
      ACTOR_KEY_PREFIX + ":" + actorName, 
      ACTOR_MAP_COLUMN_FAMILY + ":" + entry, 
      serializer.out(content), 
      System.currentTimeMillis)
  }

  def insertActorStorageEntries(actorName: String, entries: List[Tuple2[String, AnyRef]]) = {
    import java.util.{Map, HashMap, List, ArrayList}
    val columns: Map[String, List[column_t]] = new HashMap
    for (entry <- entries) {
      val cls: List[column_t] = new ArrayList
      cls.add(new column_t(entry._1, serializer.out(entry._2), System.currentTimeMillis))
      columns.put(ACTOR_MAP_COLUMN_FAMILY, cls)
    }
    server.batch_insert_blocking(new batch_mutation_t(
      TABLE_NAME, 
      ACTOR_KEY_PREFIX + ":" + actorName, 
      columns))
  }

  def getActorStorageEntryFor(actorName: String, entry: AnyRef): Option[AnyRef] = {
    try {
      val column = server.get_column(TABLE_NAME, ACTOR_KEY_PREFIX + ":" + actorName, ACTOR_MAP_COLUMN_FAMILY + ":" + entry)
      Some(serializer.in(column.value))
    } catch { case e => None }
  }

  def getActorStorageFor(actorName: String): List[Tuple2[String, AnyRef]]  = {
    val columns = server.get_columns_since(TABLE_NAME, ACTOR_KEY_PREFIX, ACTOR_MAP_COLUMN_FAMILY, -1)
      .toArray.toList.asInstanceOf[List[org.apache.cassandra.service.column_t]]
    for {
      column <- columns
      col = (column.columnName, serializer.in(column.value))
    } yield col
  }
  
  def getActorStorageSizeFor(actorName: String): Int = 
    server.get_column_count(TABLE_NAME, ACTOR_KEY_PREFIX + ":" + actorName, ACTOR_MAP_COLUMN_FAMILY)

  def removeActorStorageFor(actorName: String) = 
    server.remove(TABLE_NAME, ACTOR_KEY_PREFIX + ":" + actorName, ACTOR_MAP_COLUMN_FAMILY, System.currentTimeMillis, false)

  def getActorStorageRange(actorName: String, start: Int, count: Int): List[Tuple2[String, AnyRef]] =
    server.get_slice(TABLE_NAME, ACTOR_KEY_PREFIX + ":" + actorName, ACTOR_MAP_COLUMN_FAMILY, start, count)
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

