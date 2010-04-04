/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.jxee

import se.scalablesolutions.akka.remote.BootableRemoteActorService
import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.camel.service.CamelService
import se.scalablesolutions.akka.config.Config
import se.scalablesolutions.akka.util.{Logging, Bootable}

import javax.servlet.{ServletContextListener, ServletContextEvent}
 
 /**
  * This class can be added to web.xml mappings as a listener to start and shutdown Akka.
  */ 
class AkkaJxEELifecycle extends ServletContextListener {
   lazy val loader = new AkkaLoader
     
   def contextDestroyed(e: ServletContextEvent): Unit = 
     loader.shutdown
     
   def contextInitialized(e: ServletContextEvent): Unit = 
     loader.boot(true, new BootableActorLoaderService with BootableRemoteActorService with CamelService)
 }