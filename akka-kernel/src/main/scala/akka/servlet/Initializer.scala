/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.servlet

import akka.cluster.BootableRemoteActorService
import akka.actor.BootableActorLoaderService
import akka.config.Config
import akka.util.{ Bootable, AkkaLoader }

import javax.servlet.{ ServletContextListener, ServletContextEvent }

/**
 * This class can be added to web.xml mappings as a listener to start and postStop Akka.
 *
 * <web-system>
 * ...
 *  <listener>
 *    <listener-class>akka.servlet.Initializer</listener-class>
 *  </listener>
 * ...
 * </web-system>
 */
class Initializer extends ServletContextListener {
  lazy val loader = new AkkaLoader

  def contextDestroyed(e: ServletContextEvent): Unit =
    loader.shutdown

  def contextInitialized(e: ServletContextEvent): Unit =
    loader.boot(true, new BootableActorLoaderService with BootableRemoteActorService)
}
