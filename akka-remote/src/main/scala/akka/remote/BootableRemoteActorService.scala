/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.remote

import akka.config.Config.config
import akka.actor. {Actor, BootableActorLoaderService}
import akka.util. {ReflectiveAccess, Bootable, Logging}

/**
 * This bundle/service is responsible for booting up and shutting down the remote actors facility
 * <p/>
 * It is used in Kernel
 */
trait BootableRemoteActorService extends Bootable with Logging {
  self: BootableActorLoaderService =>

  protected lazy val remoteServerThread = new Thread(new Runnable() {
    def run = Actor.remote.start(self.applicationLoader.getOrElse(null)) //Use config host/port
  }, "Akka Remote Service")

  def startRemoteService = remoteServerThread.start

  abstract override def onLoad = {
    if (ReflectiveAccess.isRemotingEnabled && RemoteServerSettings.isRemotingEnabled) {
      log.slf4j.info("Initializing Remote Actors Service...")
      startRemoteService
      log.slf4j.info("Remote Actors Service initialized")
    }
    super.onLoad
  }

  abstract override def onUnload = {
    log.slf4j.info("Shutting down Remote Actors Service")
    Actor.remote.shutdown
    if (remoteServerThread.isAlive) remoteServerThread.join(1000)
    log.slf4j.info("Remote Actors Service has been shut down")
    super.onUnload
  }
}
