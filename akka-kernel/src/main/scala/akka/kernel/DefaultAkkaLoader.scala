/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.config.Config
import akka.util.{ Bootable, AkkaLoader }
import akka.cluster.BootableRemoteActorService
import akka.actor.BootableActorLoaderService

class DefaultAkkaLoader extends AkkaLoader {
  def boot(): Unit = boot(true, new EmbeddedAppServer with BootableActorLoaderService with BootableRemoteActorService)
}

/**
 * Can be used to boot Akka
 *
 * java -cp ... akka.http.Main
 */
object Main extends DefaultAkkaLoader {
  def main(args: Array[String]) = boot
}
