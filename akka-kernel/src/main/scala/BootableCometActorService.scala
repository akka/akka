/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
 
package se.scalablesolutions.akka

import com.sun.grizzly.http.SelectorThread
import com.sun.grizzly.http.servlet.ServletAdapter
import com.sun.grizzly.standalone.StaticStreamAlgorithm

import javax.ws.rs.core.UriBuilder
import se.scalablesolutions.akka.comet.AkkaServlet
import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.util.{Bootable,Logging}

/**
 * Handles the Akka Comet Support (load/unload)
 */
trait BootableCometActorService extends Bootable with Logging {
  self : BootableActorLoaderService =>
  
  import Config._
  
  val REST_HOSTNAME = config.getString("akka.rest.hostname", "localhost")
  val REST_URL = "http://" + REST_HOSTNAME
  val REST_PORT = config.getInt("akka.rest.port", 9998)
  protected var jerseySelectorThread: Option[SelectorThread] = None
  
  abstract override def onLoad   = {
    super.onLoad
    if(config.getBool("akka.rest.service", true)){
    
      val uri = UriBuilder.fromUri(REST_URL).port(REST_PORT).build()

      val scheme = uri.getScheme
      if (!scheme.equalsIgnoreCase("http")) throw new IllegalArgumentException(
        "The URI scheme, of the URI " + REST_URL + ", must be equal (ignoring case) to 'http'")
        
      log.info("Attempting to start REST service on uri [%s]",uri)

      val adapter = new ServletAdapter
      adapter.setHandleStaticResources(true)
      adapter.setServletInstance(new AkkaServlet)
      adapter.setContextPath(uri.getPath)
      //Using autodetection for now
      //adapter.addInitParameter("cometSupport", "org.atmosphere.container.GrizzlyCometSupport")
      if (HOME.isDefined) adapter.setRootFolder(HOME.get + "/deploy/root")
      log.info("REST service root path [%s] and context path [%s]", adapter.getRootFolder, adapter.getContextPath)

      val ah = new com.sun.grizzly.arp.DefaultAsyncHandler
      ah.addAsyncFilter(new com.sun.grizzly.comet.CometAsyncFilter)
      jerseySelectorThread = Some(new SelectorThread).map { t =>
          t.setAlgorithmClassName(classOf[StaticStreamAlgorithm].getName)
          t.setPort(REST_PORT)
          t.setAdapter(adapter)
          t.setEnableAsyncExecution(true)
          t.setAsyncHandler(ah)
              t.listen
              t }

      log.info("REST service started successfully. Listening to port [%s]", REST_PORT)
    }
  }
  
  abstract override def onUnload = {
        super.onUnload
        
        if (jerseySelectorThread.isDefined) {
        log.info("Shutting down REST service (Jersey)")
        jerseySelectorThread.get.stopEndpoint
      }
  }
}