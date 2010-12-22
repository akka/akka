/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.servlet

import akka.remote.BootableRemoteActorService
import akka.actor.BootableActorLoaderService
import akka.config.Config
import akka.util.{Logging, Bootable}

import javax.servlet.{ServletContextListener, ServletContextEvent}

 /**
  * This class can be added to web.xml mappings as a listener to start and postStop Akka.
  *
  *<web-app>
  * ...
  *  <listener>
  *    <listener-class>akka.servlet.Initializer</listener-class>
  *  </listener>
  * ...
  *</web-app>
  */
class Initializer extends ServletContextListener {
   lazy val loader = new AkkaLoader

   def contextDestroyed(e: ServletContextEvent): Unit =
     loader.shutdown

   def contextInitialized(e: ServletContextEvent): Unit =
     loader.boot(true, new BootableActorLoaderService with BootableRemoteActorService)
 }
