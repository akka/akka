/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.http

import akka.config.Config
import akka.util.{Logging, Bootable}
import akka.camel.CamelService
import akka.remote.BootableRemoteActorService
import akka.actor.BootableActorLoaderService
import akka.servlet.AkkaLoader

class DefaultAkkaLoader extends AkkaLoader {
  def boot(): Unit = boot(true,
    new EmbeddedAppServer with BootableActorLoaderService
    with BootableRemoteActorService
    with CamelService)
}


/**
 * Can be used to boot Akka
 *
 * java -cp ... akka.http.Main
 */
object Main extends DefaultAkkaLoader {
  def main(args: Array[String]) = boot
}
