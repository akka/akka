/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.remote

import akka.actor.BootableActorLoaderService
import akka.util.{Bootable, Logging}
import akka.config.Config.config

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
    if (RemoteServer.isRemotingEnabled) {
      log.slf4j.info("Initializing Remote Actors Service...")
      startRemoteService
      log.slf4j.info("Remote Actors Service initialized")
    }
    super.onLoad
  }

  abstract override def onUnload = {
    log.slf4j.info("Shutting down Remote Actors Service")
    RemoteNode.shutdown
    if (remoteServerThread.isAlive) remoteServerThread.join(1000)
    log.slf4j.info("Remote Actors Service has been shut down")
    super.onUnload
  }
}
