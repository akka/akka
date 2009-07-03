/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

//import org.apache.zookeeper.jmx.ManagedUtil
//import org.apache.zookeeper.server.persistence.FileTxnSnapLog
//import org.apache.zookeeper.server.ServerConfig
//import org.apache.zookeeper.server.NIOServerCnxn

//import voldemort.client.{SocketStoreClientFactory, StoreClient, StoreClientFactory}
//import voldemort.server.{VoldemortConfig, VoldemortServer}
//import voldemort.versioning.Versioned

import com.sun.grizzly.http.SelectorThread
import com.sun.jersey.api.container.grizzly.GrizzlyWebContainerFactory

import java.io.IOException
import java.net.URI
import java.util.{Map, HashMap}
import java.io.{File, IOException}

import javax.ws.rs.core.UriBuilder
import javax.management.JMException
import kernel.nio.{RemoteClient, RemoteServer}
import kernel.state.CassandraNode
import kernel.util.Logging

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends Logging {

  val SERVER_URL = "localhost"
  /*
    private[this] var storageFactory: StoreClientFactory = _
    private[this] var storageServer: VoldemortServer = _
  */

  private[this] var remoteServer: RemoteServer = _

  def main(args: Array[String]): Unit = {
    log.info("Starting Akka kernel...")
    startRemoteService
    startCassandra
    //cassandraBenchmark
      
    //startJersey
    //startZooKeeper
    //startVoldemort
    log.info("Akka kernel started successfully")
  }


  private[akka] def startRemoteService = {
    // FIXME manage remote serve thread for graceful shutdown
    val remoteServerThread = new Thread(new Runnable() {
       def run = {
         RemoteServer.start
       }
    })
    remoteServerThread.start
    Thread.sleep(1000) // wait for server to start up
  }

  private[akka] def startCassandra = {
    CassandraNode.start
  }

  private[akka] def startJersey = {
    val JERSEY_SERVER_URL = "http://" + SERVER_URL + "/"
    val JERSEY_SERVER_PORT = 9998
    val JERSEY_REST_CLASSES_ROOT_PACKAGE = "se.scalablesolutions.akka.kernel"
    val JERSEY_BASE_URI = UriBuilder.fromUri(JERSEY_SERVER_URL).port(getPort(JERSEY_SERVER_PORT)).build()
    val initParams = new java.util.HashMap[String, String]
    initParams.put("com.sun.jersey.config.property.packages", JERSEY_REST_CLASSES_ROOT_PACKAGE)
    val threadSelector = GrizzlyWebContainerFactory.create(JERSEY_BASE_URI, initParams)
    // TODO: handle shutdown of Jersey in separate thread
    // TODO: spawn main in new thread an communicate using socket
    System.in.read
    threadSelector.stopEndpoint
  }

  private def cassandraBenchmark = {
    val NR_ENTRIES = 100000
 
    println("=================================================")
    var start = System.currentTimeMillis
    for (i <- 1 to NR_ENTRIES) CassandraNode.insertMapStorageEntryFor("test", i.toString, "data")
    var end = System.currentTimeMillis
    println("Writes per second: " + NR_ENTRIES / ((end - start).toDouble / 1000))

    /*
FIXME: batch_insert fails with the following exception: 

ERROR - Exception was generated at : 04/27/2009 15:26:35 on thread main
[B cannot be cast to org.apache.cassandra.db.WriteResponse
java.lang.ClassCastException: [B cannot be cast to org.apache.cassandra.db.WriteResponse
    at org.apache.cassandra.service.WriteResponseResolver.resolve(WriteResponseResolver.java:50)
    at org.apache.cassandra.service.WriteResponseResolver.resolve(WriteResponseResolver.java:31)
    at org.apache.cassandra.service.QuorumResponseHandler.get(QuorumResponseHandler.java:101)
    at org.apache.cassandra.service.StorageProxy.insertBlocking(StorageProxy.java:135)
    at org.apache.cassandra.service.CassandraServer.batch_insert_blocking(CassandraServer.java:489)
    at se.scalablesolutions.akka.kernel.CassandraNode$.insertHashEntries(CassandraNode.scala:59)
    at se.scalablesolutions.akka.kernel.Kernel$.cassandraBenchmark(Kernel.scala:91)
    at se.scalablesolutions.akka.kernel.Kernel$.main(Kernel.scala:52)
    at se.scalablesolutions.akka.kernel.Kernel.main(Kernel.scala)
 
    println("=================================================")
    var start = System.currentTimeMillis
    println(start)
    val entries = new scala.collection.mutable.ArrayBuffer[Tuple2[String, String]]
    for (i <- 1 to NR_ENTRIES) entries += (i.toString, "data")
    CassandraNode.insertHashEntries("test", entries.toList)
    var end = System.currentTimeMillis
    println("Writes per second - batch: " + NR_ENTRIES / ((end - start).toDouble / 1000))
    */
    println("=================================================")
    start = System.currentTimeMillis
    for (i <- 1 to NR_ENTRIES) CassandraNode.getMapStorageEntryFor("test", i.toString)
    end = System.currentTimeMillis
    println("Reads per second: " + NR_ENTRIES / ((end - start).toDouble / 1000))

    System.exit(0)
  }
  
//  private[akka] def startVoldemort = {
//  val VOLDEMORT_SERVER_URL = "tcp://" + SERVER_URL
//  val VOLDEMORT_SERVER_PORT = 6666
//  val VOLDEMORT_BOOTSTRAP_URL = VOLDEMORT_SERVER_URL + ":" + VOLDEMORT_SERVER_PORT
//    // Start Voldemort server
//    val config = VoldemortConfig.loadFromVoldemortHome(Boot.HOME)
//    storageServer = new VoldemortServer(config)
//    storageServer.start
//    log.info("Replicated persistent storage server started at %s", VOLDEMORT_BOOTSTRAP_URL)
//
//    // Create Voldemort client factory
//    val numThreads = 10
//    val maxQueuedRequests = 10
//    val maxConnectionsPerNode = 10
//    val maxTotalConnections = 100
//    storageFactory = new SocketStoreClientFactory(
//      numThreads,
//      numThreads,
//      maxQueuedRequests,
//      maxConnectionsPerNode,
//      maxTotalConnections,
//      VOLDEMORT_BOOTSTRAP_URL)
//
//    val name = this.getClass.getName
//    val storage = getStorageFor("actors")
////    val value = storage.get(name)
//    val value = new Versioned("state")
//    //value.setObject("state")
//    storage.put(name, value)
//  }
//
//  private[akka] def getStorageFor(storageName: String): StoreClient[String, String] =
//    storageFactory.getStoreClient(storageName)

  // private[akka] def startZooKeeper = {
  //  val ZOO_KEEPER_SERVER_URL = SERVER_URL
  //  val ZOO_KEEPER_SERVER_PORT = 9898
  //   try {
  //     ManagedUtil.registerLog4jMBeans
  //     ServerConfig.parse(args)
  //   } catch {
  //     case e: JMException => log.warning("Unable to register log4j JMX control: s%", e)
  //     case e => log.fatal("Error in ZooKeeper config: s%", e)
  //   }
  //   val factory = new ZooKeeperServer.Factory() {
  //     override def createConnectionFactory = new NIOServerCnxn.Factory(ServerConfig.getClientPort)
  //     override def createServer = {
  //       val server = new ZooKeeperServer
  //       val txLog = new FileTxnSnapLog(
  //         new File(ServerConfig.getDataLogDir),
  //         new File(ServerConfig.getDataDir))
  //       server.setTxnLogFactory(txLog)
  //       server
  //     }
  //   }
  //   try {
  //     val zooKeeper = factory.createServer
  //     zooKeeper.startup
  //     log.info("ZooKeeper started")
  //     // TODO: handle clean shutdown as below in separate thread
  //     // val cnxnFactory = serverFactory.createConnectionFactory
  //     // cnxnFactory.setZooKeeperServer(zooKeeper)
  //     // cnxnFactory.join
  //     // if (zooKeeper.isRunning) zooKeeper.shutdown
  //   } catch { case e => log.fatal("Unexpected exception: s%",e) }
  // }

  private def getPort(defaultPort: Int) = {
    val port = System.getenv("JERSEY_HTTP_PORT")
    if (null != port) Integer.parseInt(port)
    else defaultPort;
  }
}

//import javax.ws.rs.{Produces, Path, GET}
//  @GET
//  @Produces("application/json")
//  @Path("/network/{id: [0-9]+}/{nid}")
//  def getUserByNetworkId(@PathParam {val value = "id"} id: Int, @PathParam {val value = "nid"} networkId: String): User = {
//    val q = em.createQuery("SELECT u FROM User u WHERE u.networkId = :id AND u.networkUserId = :nid")
//    q.setParameter("id", id)
//    q.setParameter("nid", networkId)
//    q.getSingleResult.asInstanceOf[User]
//  }

