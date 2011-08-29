/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.kernel

import akka.http.EmbeddedAppServer
import akka.util.AkkaLoader
import akka.cluster.BootableRemoteActorService
import akka.actor.BootableActorLoaderService
import akka.camel.CamelService

import java.util.concurrent.CountDownLatch

object Main {
  val keepAlive = new CountDownLatch(2)

  def main(args: Array[String]) = {
    Kernel.boot
    keepAlive.await
  }
}

/**
 * The Akka Kernel, is used to start And postStop Akka in standalone/kernel mode.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends AkkaLoader {

  def boot(): Unit = boot(true, new EmbeddedAppServer with BootableActorLoaderService with BootableRemoteActorService with CamelService)

  // For testing purposes only
  def startRemoteService(): Unit = bundles.foreach(_ match {
    case x: BootableRemoteActorService ⇒ x.startRemoteService
    case _                             ⇒
  })
}
