/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka

import se.scalablesolutions.akka.remote.BootableRemoteActorService
import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.util.{Logging,Bootable}

import javax.servlet.{ServletContextListener, ServletContextEvent}

object Main {
  def main(args: Array[String]) = Kernel.boot
}

/**
 * The Akka Kernel. 
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends Logging {
  @volatile private var hasBooted = false
  
  private val startTime = System.currentTimeMillis
  
  /**
   * Holds a reference to the services that has been booted
   */
  @volatile private var bundles : Option[Bootable] = None

  /**
   *  Boots up the Kernel with default bootables
   */
  def boot : Unit = boot(true, new BootableActorLoaderService with BootableRemoteActorService with BootableCometActorService)

  /**
   * Boots up the Kernel. 
   * If you pass in false as parameter then the Akka banner is not printed out.
   */   
  def boot(withBanner: Boolean, b : Bootable): Unit = synchronized {
    if (!hasBooted) {
      if (withBanner) printBanner
      log.info("Starting Akka...")
      b.onLoad
      Thread.currentThread.setContextClassLoader(getClass.getClassLoader)
      log.info("Akka started successfully")
      hasBooted = true
      bundles = Some(b)
    }
  }

  /**
   * Shuts down the kernel, unloads all of the bundles
   */
  def shutdown = synchronized {
    if (hasBooted) {
      log.info("Shutting down Akka...")
      bundles.foreach(_.onUnload)
      bundles = None
      log.info("Akka succesfully shut down")
    }
  }

  //For testing purposes only
  def startRemoteService : Unit = bundles.foreach( _ match {
    case x : BootableRemoteActorService => x.startRemoteService
    case _ =>
  })

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
    log.info("     Running version %s", Config.VERSION)
    log.info("==============================")
  }
}
 
 /*
  And this one can be added to web.xml mappings as a listener to boot and shutdown Akka
 */
 
class Kernel extends ServletContextListener {
   def contextDestroyed(e : ServletContextEvent) : Unit = Kernel.shutdown
   def contextInitialized(e : ServletContextEvent) : Unit = Kernel.boot(true,new BootableActorLoaderService with BootableRemoteActorService)
 }