/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.kernel

import se.scalablesolutions.akka.servlet.AkkaLoader
import se.scalablesolutions.akka.remote.BootableRemoteActorService
import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.camel.CamelService
import se.scalablesolutions.akka.config.Config

object Main {
  def main(args: Array[String]) = Kernel.boot
}

/**
 * The Akka Kernel, is used to start And shutdown Akka in standalone/kernel mode.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends AkkaLoader {
  /**
   * Boots up the Kernel with default bootables
   */
  def boot(): Unit = boot(true,
    new EmbeddedAppServer with BootableActorLoaderService
    with BootableRemoteActorService
    with CamelService)

  //For testing purposes only
  def startRemoteService(): Unit = bundles.foreach( _ match {
    case x: BootableRemoteActorService => x.startRemoteService
    case _ =>
  })
}
