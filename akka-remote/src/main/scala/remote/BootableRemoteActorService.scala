/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.util.{Bootable, Logging}
import se.scalablesolutions.akka.config.Config.config

/**
 * This bundle/service is responsible for booting up and shutting down the remote actors facility
 * <p/>
 * It is used in Kernel
 */
trait BootableRemoteActorService extends Bootable with Logging {
  self: BootableActorLoaderService =>

  protected lazy val remoteServerThread = new Thread(new Runnable() {
    def run = {
      if (self.applicationLoader.isDefined) RemoteNode.start(self.applicationLoader.get)
      else RemoteNode.start
    }
  }, "Akka Remote Service")

  def startRemoteService = remoteServerThread.start

  abstract override def onLoad = {
    if (config.getBool("akka.remote.server.service", true)) {
      if (config.getBool("akka.remote.cluster.service", true)) Cluster.start(self.applicationLoader)
      log.info("Initializing Remote Actors Service...")
      startRemoteService
      log.info("Remote Actors Service initialized")
    }
    super.onLoad
  }

  abstract override def onUnload = {
    log.info("Shutting down Remote Actors Service")
    RemoteNode.shutdown
    if (remoteServerThread.isAlive) remoteServerThread.join(1000)
    log.info("Shutting down Cluster")
    Cluster.shutdown
    log.info("Remote Actors Service has been shut down")
    super.onUnload
  }
}
