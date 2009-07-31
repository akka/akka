/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import com.sun.grizzly.http.SelectorThread
import com.sun.grizzly.http.servlet.ServletAdapter
import com.sun.grizzly.standalone.StaticStreamAlgorithm

import javax.ws.rs.core.UriBuilder
import java.io.File
import java.net.URLClassLoader

import net.lag.configgy.{Config, Configgy, RuntimeEnvironment}

import kernel.jersey.AkkaServlet
import kernel.nio.RemoteServer
import kernel.state.CassandraStorage
import kernel.util.Logging

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends Logging {
  @volatile private var hasBooted = false
  
  val config = setupConfig

  val BOOT_CLASSES = config.getList("akka.boot")

  val RUN_REMOTE_SERVICE = config.getBool("akka.remote.service", true)
  val STORAGE_SYSTEM = config.getString("akka.storage.system", "cassandra")

  val RUN_REST_SERVICE = config.getBool("akka.rest.service", true)
  val REST_HOSTNAME = kernel.Kernel.config.getString("akka.rest.hostname", "localhost")
  val REST_URL = "http://" + REST_HOSTNAME
  val REST_PORT = kernel.Kernel.config.getInt("akka.rest.port", 9998)


  // FIXME add API to shut server down gracefully
  private var remoteServer: RemoteServer = _
  private var jerseySelectorThread: SelectorThread = _
  private val startTime = System.currentTimeMillis

  def main(args: Array[String]) = boot
  
  def boot = synchronized {
    if (!hasBooted) {
      printBanner
      log.info("Starting Akka kernel...")

      if (RUN_REMOTE_SERVICE) startRemoteService

      STORAGE_SYSTEM match {
        case "cassandra" =>     startCassandra
        case "terracotta" =>    throw new UnsupportedOperationException("terracotta storage backend is not yet supported")
        case "redis" =>         throw new UnsupportedOperationException("redis storage backend is not yet supported")
        case "voldemort" =>     throw new UnsupportedOperationException("voldemort storage backend is not yet supported")
        case "tokyo-cabinet" => throw new UnsupportedOperationException("tokyo-cabinet storage backend is not yet supported")
        case _ =>               throw new UnsupportedOperationException("Unknown storage system [" + STORAGE_SYSTEM + "]")
      }

      if (RUN_REST_SERVICE) startJersey

      runApplicationBootClasses

      log.info("Akka kernel started successfully")
      hasBooted = true
    }
  }
  
  def uptime = (System.currentTimeMillis - startTime) / 1000

  def setupConfig: Config = {
    try {
      Configgy.configure(akka.Boot.CONFIG + "/akka.conf")
      val runtime = new RuntimeEnvironment(getClass)
      //runtime.load(args)
      val config = Configgy.config
      config.registerWithJmx("com.scalablesolutions.akka.config")

      // FIXME fix Configgy JMX subscription to allow management
      // config.subscribe { c => configure(c.getOrElse(new Config)) }
      config
    } catch {
      case e: net.lag.configgy.ParseException => throw new Error("Could not retreive the akka.conf config file. Make sure you have set the AKKA_HOME environment variable to the root of the distribution.")
    }
  }

  private[akka] def runApplicationBootClasses = {
    val HOME = try { System.getenv("AKKA_HOME") } catch { case e: NullPointerException => throw new IllegalStateException("AKKA_HOME system variable needs to be set. Should point to the root of the Akka distribution.") }
    val CLASSES = HOME + "/kernel/target/classes" // FIXME remove for dist
    val LIB = HOME + "/lib"
    val CONFIG = HOME + "/config"
    val DEPLOY = HOME + "/deploy"
    val DEPLOY_DIR = new File(DEPLOY)
    if (!DEPLOY_DIR.exists) { log.error("Could not find a deploy directory at [" + DEPLOY + "]"); System.exit(-1) }
    val toDeploy = for (f <- DEPLOY_DIR.listFiles().toArray.toList.asInstanceOf[List[File]]) yield f.toURL
    log.info("Deploying applications from [%s]: [%s]", DEPLOY, toDeploy.toArray.toList)
    val loader = new URLClassLoader(toDeploy.toArray, getClass.getClassLoader)
    if (BOOT_CLASSES.isEmpty) throw new IllegalStateException("No boot class specificed. Add an application boot class to the 'akka.conf' file such as 'boot = \"com.biz.myapp.Boot\"")
    for (clazz <- BOOT_CLASSES) {
      log.info("Booting with boot class [%s]", clazz)
      loader.loadClass(clazz).newInstance
    }
  }
  
  private[akka] def startRemoteService = {
    // FIXME manage remote serve thread for graceful shutdown
    val remoteServerThread = new Thread(new Runnable() {
       def run = RemoteServer.start
    }, "akka remote service")
    remoteServerThread.start
  }

  private[akka] def startCassandra = if (config.getBool("akka.storage.cassandra.service", true)) {
    System.setProperty("cassandra", "")
    System.setProperty("storage-config", akka.Boot.CONFIG + "/")
    println("------------------------- " + akka.Boot.CONFIG + "/")
    CassandraStorage.start
  }

  private[akka] def startJersey = {
    val uri = UriBuilder.fromUri(REST_URL).port(REST_PORT).build()
    val adapter = new ServletAdapter
    val servlet = new AkkaServlet
    adapter.setServletInstance(servlet)
    adapter.setContextPath(uri.getPath)

    val scheme = uri.getScheme
    if (!scheme.equalsIgnoreCase("http")) throw new IllegalArgumentException("The URI scheme, of the URI " + REST_URL + ", must be equal (ignoring case) to 'http'")

    jerseySelectorThread = new SelectorThread
    jerseySelectorThread.setAlgorithmClassName(classOf[StaticStreamAlgorithm].getName)
    jerseySelectorThread.setPort(REST_PORT)
    jerseySelectorThread.setAdapter(adapter)
    jerseySelectorThread.listen
    log.info("REST service started successfully. Listening to port [" + REST_PORT + "]")
  }

  private def printBanner = {
    log.info(
"""==============================
        __    __
 _____  |  | _|  | _______
 \__  \ |  |/ /  |/ /\__  \
  / __ \|    <|    <  / __ \_
 (____  /__|_ \__|_ \(____  /
      \/     \/    \/     \/
""")
    log.info("     Running version " + kernel.Kernel.config.getString("akka.version", "awesome"))
    log.info("==============================")
  }
  
  private def cassandraBenchmark = {
    val NR_ENTRIES = 100000

    println("=================================================")
    var start = System.currentTimeMillis
    for (i <- 1 to NR_ENTRIES) CassandraStorage.insertMapStorageEntryFor("test", i.toString, "data")
    var end = System.currentTimeMillis
    println("Writes per second: " + NR_ENTRIES / ((end - start).toDouble / 1000))

    println("=================================================")
    start = System.currentTimeMillis
    val entries = new scala.collection.mutable.ArrayBuffer[Tuple2[String, String]]
    for (i <- 1 to NR_ENTRIES) entries += (i.toString, "data")
    CassandraStorage.insertMapStorageEntriesFor("test", entries.toList)
    end = System.currentTimeMillis
    println("Writes per second - batch: " + NR_ENTRIES / ((end - start).toDouble / 1000))
    
    println("=================================================")
    start = System.currentTimeMillis
    for (i <- 1 to NR_ENTRIES) CassandraStorage.getMapStorageEntryFor("test", i.toString)
    end = System.currentTimeMillis
    println("Reads per second: " + NR_ENTRIES / ((end - start).toDouble / 1000))

    System.exit(0)
  }
}



  
/*
  //import voldemort.client.{SocketStoreClientFactory, StoreClient, StoreClientFactory}
  //import voldemort.server.{VoldemortConfig, VoldemortServer}
  //import voldemort.versioning.Versioned

    private[this] var storageFactory: StoreClientFactory = _
    private[this] var storageServer: VoldemortServer = _
  */

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
  //import org.apache.zookeeper.jmx.ManagedUtil
  //import org.apache.zookeeper.server.persistence.FileTxnSnapLog
  //import org.apache.zookeeper.server.ServerConfig
  //import org.apache.zookeeper.server.NIOServerCnxn
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
