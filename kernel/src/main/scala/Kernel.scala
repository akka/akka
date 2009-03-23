/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import org.apache.zookeeper.jmx.ManagedUtil
import org.apache.zookeeper.server.persistence.FileTxnSnapLog
import org.apache.zookeeper.server.ServerConfig
import org.apache.zookeeper.server.NIOServerCnxn

import voldemort.client.{SocketStoreClientFactory, StoreClient, StoreClientFactory}
import voldemort.server.{VoldemortConfig, VoldemortServer}
import voldemort.versioning.Versioned

import com.sun.grizzly.http.SelectorThread
import com.sun.jersey.api.container.grizzly.GrizzlyWebContainerFactory

import java.io.IOException
import java.net.URI
import java.util.{Map, HashMap}
import java.io.{File, IOException}

import javax.ws.rs.core.UriBuilder
import javax.management.JMException

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends Logging {

  val SERVER_URL = "localhost"
  
  val JERSEY_SERVER_URL = "http://" + SERVER_URL + "/"
  val JERSEY_SERVER_PORT = 9998
  val JERSEY_REST_CLASSES_ROOT_PACKAGE = "se.scalablesolutions.akka.kernel"
  val JERSEY_BASE_URI = UriBuilder.fromUri(JERSEY_SERVER_URL).port(getPort(JERSEY_SERVER_PORT)).build()

  val VOLDEMORT_SERVER_URL = "tcp://" + SERVER_URL
  val VOLDEMORT_SERVER_PORT = 6666
  val VOLDEMORT_BOOTSTRAP_URL = VOLDEMORT_SERVER_URL + ":" + VOLDEMORT_SERVER_PORT

  val ZOO_KEEPER_SERVER_URL = SERVER_URL
  val ZOO_KEEPER_SERVER_PORT = 9898

  private[this] var storageFactory: StoreClientFactory = _
  private[this] var storageServer: VoldemortServer = _
   
  def main(args: Array[String]): Unit = {
    //startZooKeeper
    startVoldemort
    //val threadSelector = startJersey

    // TODO: handle shutdown of Jersey in separate thread
    // TODO: spawn main in new thread an communicate using socket
    //System.in.read
    //threadSelector.stopEndpoint
  }

  private[akka] def startJersey: SelectorThread = {
    val initParams = new java.util.HashMap[String, String]
    initParams.put(
      "com.sun.jersey.config.property.packages",
      JERSEY_REST_CLASSES_ROOT_PACKAGE)
    GrizzlyWebContainerFactory.create(JERSEY_BASE_URI, initParams)
  }

   private[akka] def startVoldemort = {
    // Start Voldemort server
    val config = VoldemortConfig.loadFromVoldemortHome(Boot.HOME)
    storageServer = new VoldemortServer(config)
    storageServer.start
    log.info("Replicated persistent storage server started at %s", VOLDEMORT_BOOTSTRAP_URL)

    // Create Voldemort client factory
    val numThreads = 10
    val maxQueuedRequests = 10
    val maxConnectionsPerNode = 10
    val maxTotalConnections = 100
    storageFactory = new SocketStoreClientFactory(
      numThreads,
      numThreads,
      maxQueuedRequests,
      maxConnectionsPerNode,
      maxTotalConnections,
      VOLDEMORT_BOOTSTRAP_URL)

    val name = this.getClass.getName
    val storage = getStorageFor("actors")
//    val value = storage.get(name)
    val value = new Versioned("state")
    //value.setObject("state")
    storage.put(name, value)
  }
  
  private[akka] def getStorageFor(storageName: String): StoreClient[String, String] =
    storageFactory.getStoreClient(storageName)

  // private[akka] def startZooKeeper = {
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

