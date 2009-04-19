/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import java.io.File

import com.facebook.thrift.protocol.TBinaryProtocol
import com.facebook.thrift.protocol.TProtocolFactory
import com.facebook.thrift.server.TThreadPoolServer
import com.facebook.thrift.transport.TServerSocket
import com.facebook.thrift.transport.TTransportException
import com.facebook.thrift.transport.TTransportFactory
import com.facebook.thrift.TProcessorFactory

import org.apache.cassandra.config.DatabaseDescriptor
import org.apache.cassandra.service._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class CassandraNode extends Logging {
  val server = try {
    val cassandra = new CassandraServer
    cassandra.start
    cassandra
  } catch {
    case e =>
      log.error("Could not start up persistent storage node")
      throw e
  }

  private val serverEngine: TThreadPoolServer = try {
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

  def start = {
    scala.actors.Actor.actor {
      log.info("Persistent storage node starting up...");
      serverEngine.serve
    }
    log.info("Persistent storage node starting up 2222...");
    server.insert("akka", "TestActor", "hash:data", "some data", System.currentTimeMillis)
    val column = server.get_column("akka", "TestActor", "hash:data")
    log.info("column: " + column)

  }
  def stop = {
    log.info("Persistent storage node shutting down...")
    serverEngine.stop
  }
}
