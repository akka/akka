/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import java.io.File

import org.apache.cassandra.config.DatabaseDescriptor
import org.apache.cassandra.service._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final object CassandraNode extends Logging {

  val TABLE_NAME = "akka"
  val ACTOR_KEY_PREFIX = "actor"
  val ACTOR_MAP_COLUMN_FAMILY = "map"
  
  // TODO: is this server thread-safe or needed to be wrapped up in an actor?
  private[this] val server = new CassandraServer

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

  def stop = server.shutdown

  def insertActorStorageEntry(actorName: String, entry: String, content: String) = {
    server.insert(
      TABLE_NAME, 
      ACTOR_KEY_PREFIX + ":" + actorName, 
      ACTOR_MAP_COLUMN_FAMILY + ":" + entry, 
      content, 
      System.currentTimeMillis)
  }

  def insertActorStorageEntries(actorName: String, entries: List[Tuple2[String, String]]) = {
    import java.util.{Map, HashMap, List, ArrayList}
    val columns: Map[String, List[column_t]] = new HashMap
    for (entry <- entries) {
      val cls: List[column_t] = new ArrayList
      cls.add(new column_t(entry._1, entry._2, System.currentTimeMillis))
      columns.put(ACTOR_MAP_COLUMN_FAMILY, cls)
    }
    server.batch_insert_blocking(new batch_mutation_t(
      TABLE_NAME, 
      ACTOR_KEY_PREFIX + ":" + actorName, 
      columns, 
      new HashMap[String, List[column_t]]))
  }

  def getActorStorageEntryFor(actorName: String, entry: String): Option[String] = {
    try {
      val column = server.get_column(TABLE_NAME, ACTOR_KEY_PREFIX + ":" + actorName, ACTOR_MAP_COLUMN_FAMILY + ":" + entry)
      Some(column.value)
    } catch { case e => None }
  }

  def getActorStorageFor(actorName: String): List[Tuple2[String, String]]  = {
    val columns = server.get_columns_since(TABLE_NAME, ACTOR_KEY_PREFIX, ACTOR_MAP_COLUMN_FAMILY, -1)
      .toArray.toList.asInstanceOf[List[org.apache.cassandra.service.column_t]]
    for {
      column <- columns
      col = (column.columnName, column.value)
    } yield col
  }
  
  def getActorStorageSizeFor(actorName: String): Int = 
    server.get_column_count(TABLE_NAME, ACTOR_KEY_PREFIX + ":" + actorName, ACTOR_MAP_COLUMN_FAMILY)

  def removeActorStorageFor(actorName: String) = 
    server.remove(TABLE_NAME, ACTOR_KEY_PREFIX + ":" + actorName, ACTOR_MAP_COLUMN_FAMILY)

  def getActorStorageRange(actorName: String, start: Int, count: Int): List[Tuple2[String, String]] =
    server.get_slice(TABLE_NAME, ACTOR_KEY_PREFIX + ":" + actorName, ACTOR_MAP_COLUMN_FAMILY, start, count)
      .toArray.toList.asInstanceOf[List[Tuple2[String, String]]]
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

