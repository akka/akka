/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.http

import javax.ws.rs.core.UriBuilder
import javax.servlet.ServletConfig
import java.io.File

import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.util.{Bootable, Logging}
import se.scalablesolutions.akka.comet.AkkaServlet

import org.eclipse.jetty.xml.XmlConfiguration
import org.eclipse.jetty.server.{Handler, Server}
import org.eclipse.jetty.server.handler.{HandlerList, HandlerCollection, ContextHandler}

/**
 * Handles the Akka Comet Support (load/unload)
 */
trait EmbeddedAppServer extends Bootable with Logging {
  self : BootableActorLoaderService =>

  import se.scalablesolutions.akka.config.Config._

  val REST_HOSTNAME = config.getString("akka.rest.hostname", "localhost")
  val REST_PORT = config.getInt("akka.rest.port", 9998)

  protected var server: Option[Server] = None

  abstract override def onLoad = {
    super.onLoad
    if (config.getBool("akka.rest.service", true)) {
      log.info("Attempting to start Akka REST service (Jersey)")
      
      System.setProperty("jetty.port",REST_PORT.toString)
      System.setProperty("jetty.host",REST_HOSTNAME)
      System.setProperty("jetty.home",HOME.getOrElse(throwNoAkkaHomeException) + "/deploy/root")

      val configuration = new XmlConfiguration(
        new File(HOME.getOrElse(throwNoAkkaHomeException) + "/config/microkernel-server.xml").toURI.toURL)
      
      server = Option(configuration.configure.asInstanceOf[Server]) map { s => //Set the correct classloader to our contexts
         applicationLoader foreach { loader =>
           //We need to provide the correct classloader to the servlets
           def setClassLoader(handlers: Seq[Handler]): Unit = {
             handlers foreach {
               case c: ContextHandler    => c.setClassLoader(loader)
               case c: HandlerCollection => setClassLoader(c.getHandlers)
               case _ =>
             }
           }
           setClassLoader(s.getHandlers)
         }
         //Start the server
         s.start()
         s
      }
      log.info("Akka REST service started (Jersey)")
    }
  }

  abstract override def onUnload = {
    super.onUnload
    server foreach { t => {
      log.info("Shutting down REST service (Jersey)")
      t.stop()
      }
    }
  }
}
