/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka

import com.sun.grizzly.http.SelectorThread
import com.sun.grizzly.http.servlet.ServletAdapter
import com.sun.grizzly.standalone.StaticStreamAlgorithm

import javax.ws.rs.core.UriBuilder
import java.io.File
import java.net.URLClassLoader

import se.scalablesolutions.akka.nio.RemoteNode
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.actor.ActorRegistry

/**
 * The Akka Kernel. 
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends Logging {
  import Config._

  val BOOT_CLASSES = config.getList("akka.boot")
  val RUN_REMOTE_SERVICE = config.getBool("akka.remote.server.service", true)
  val RUN_REST_SERVICE = config.getBool("akka.rest.service", true)
  val REST_HOSTNAME = config.getString("akka.rest.hostname", "localhost")
  val REST_URL = "http://" + REST_HOSTNAME
  val REST_PORT = config.getInt("akka.rest.port", 9998)

  // FIXME add API to shut server down gracefully
  @volatile private var hasBooted = false
  private var jerseySelectorThread: Option[SelectorThread] = None
  private val startTime = System.currentTimeMillis
  private var applicationLoader: Option[ClassLoader] = None

  private lazy val remoteServerThread = new Thread(new Runnable() {
    def run = RemoteNode.start(applicationLoader)
  }, "Akka Remote Service")

  def main(args: Array[String]) = boot

  /**
   * Boots up the Kernel. 
   */   
  def boot: Unit = boot(true)

  /**
   * Boots up the Kernel. 
   * If you pass in false as parameter then the Akka banner is not printed out.
   */   
  def boot(withBanner: Boolean): Unit = synchronized {
    if (!hasBooted) {
      if (withBanner) printBanner
      log.info("Starting Akka...")

      runApplicationBootClasses
      if (RUN_REMOTE_SERVICE) startRemoteService
      if (RUN_REST_SERVICE) startREST

      Thread.currentThread.setContextClassLoader(getClass.getClassLoader)
      log.info("Akka started successfully")
      hasBooted = true
    }
  }

  // TODO document Kernel.shutdown
  def shutdown = synchronized {
    if (hasBooted) {
      log.info("Shutting down Akka...")
      ActorRegistry.shutdownAll
      if (jerseySelectorThread.isDefined) {
        log.info("Shutting down REST service (Jersey)")
        jerseySelectorThread.get.stopEndpoint
      }
      if (remoteServerThread.isAlive) {
        log.info("Shutting down remote service")
        RemoteNode.shutdown
        remoteServerThread.join(1000)
      }
      log.info("Akka succesfully shut down")
    }
  }

  def startRemoteService = remoteServerThread.start

  def startREST = {
    val uri = UriBuilder.fromUri(REST_URL).port(REST_PORT).build()

    val scheme = uri.getScheme
    if (!scheme.equalsIgnoreCase("http")) throw new IllegalArgumentException(
      "The URI scheme, of the URI " + REST_URL + ", must be equal (ignoring case) to 'http'")

    val adapter = new ServletAdapter
    adapter.setHandleStaticResources(true)
    adapter.setServletInstance(new AkkaCometServlet)
    adapter.setContextPath(uri.getPath)
    //Using autodetection for now
    adapter.addInitParameter("cometSupport", "org.atmosphere.container.GrizzlyCometSupport")
    if (HOME.isDefined) adapter.setRootFolder(HOME.get + "/deploy/root")
    log.info("REST service root path [%s] and context path [%s]", adapter.getRootFolder, adapter.getContextPath)

    val ah = new com.sun.grizzly.arp.DefaultAsyncHandler
    ah.addAsyncFilter(new com.sun.grizzly.comet.CometAsyncFilter)
    jerseySelectorThread = Some(new SelectorThread)
    jerseySelectorThread.get.setAlgorithmClassName(classOf[StaticStreamAlgorithm].getName)
    jerseySelectorThread.get.setPort(REST_PORT)
    jerseySelectorThread.get.setAdapter(adapter)
    jerseySelectorThread.get.setEnableAsyncExecution(true)
    jerseySelectorThread.get.setAsyncHandler(ah)
    jerseySelectorThread.get.listen

    log.info("REST service started successfully. Listening to port [%s]", REST_PORT)
  }

  private def runApplicationBootClasses = {
    val loader =
    if (HOME.isDefined) {
      val CONFIG = HOME.get + "/config"
      val DEPLOY = HOME.get + "/deploy"
      val DEPLOY_DIR = new File(DEPLOY)
      if (!DEPLOY_DIR.exists) {
        log.error("Could not find a deploy directory at [%s]", DEPLOY)
        System.exit(-1)
      }
      val toDeploy = for (f <- DEPLOY_DIR.listFiles().toArray.toList.asInstanceOf[List[File]]) yield f.toURL
      log.info("Deploying applications from [%s]: [%s]", DEPLOY, toDeploy.toArray.toList)
      new URLClassLoader(toDeploy.toArray, getClass.getClassLoader)
    } else if (getClass.getClassLoader.getResourceAsStream("akka.conf") != null) {
      getClass.getClassLoader
    } else throw new IllegalStateException(
      "AKKA_HOME is not defined and no 'akka.conf' can be found on the classpath, aborting")
    for (clazz <- BOOT_CLASSES) {
      log.info("Loading boot class [%s]", clazz)
      loader.loadClass(clazz).newInstance
    }
    applicationLoader = Some(loader)
  }

  private def printBanner = {
    log.info(
"""
==============================
          __    __
 _____  |  | _|  | _______
 \__  \ |  |/ /  |/ /\__  \
  / __ \|    <|    <  / __ \_
 (____  /__|_ \__|_ \(____  /
      \/     \/    \/     \/
""")
    log.info("     Running version %s", VERSION)
    log.info("==============================")
  }
}