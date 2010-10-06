/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.http

import se.scalablesolutions.akka.config.Config
import se.scalablesolutions.akka.util.{Logging, Bootable}
import se.scalablesolutions.akka.camel.CamelService
import se.scalablesolutions.akka.remote.BootableRemoteActorService
import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.servlet.AkkaLoader

class DefaultAkkaLoader extends AkkaLoader {
  def boot(): Unit = boot(true,
    new EmbeddedAppServer with BootableActorLoaderService
    with BootableRemoteActorService
    with CamelService)
}


/**
 * Can be used to boot Akka
 *
 * java -cp ... se.scalablesolutions.akka.http.Main
 */
object Main extends DefaultAkkaLoader {
  def main(args: Array[String]) = boot
}