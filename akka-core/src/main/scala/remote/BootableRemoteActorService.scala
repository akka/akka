/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
 
package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.util.{Bootable,Logging}
import se.scalablesolutions.akka.Config.config

/**
 * This bundle/service is responsible for booting up and shutting down the remote actors facility
 * It's used in Kernel
 */

trait BootableRemoteActorService extends Bootable with Logging {
  self : BootableActorLoaderService =>

  protected lazy val remoteServerThread = new Thread(new Runnable() {
    def run = RemoteNode.start(self.applicationLoader)
  }, "Akka Remote Service")
  
  def startRemoteService = remoteServerThread.start
  
  abstract override def onLoad   = {
    if(config.getBool("akka.remote.server.service", true)){
      Cluster.start
      super.onLoad //Initialize BootableActorLoaderService before remote service
      log.info("Initializing Remote Actors Service...")
      startRemoteService
      log.info("Remote Actors Service initialized!")
    }
    else
      super.onLoad

  }
  
  abstract override def onUnload = {
        super.onUnload          
        if (remoteServerThread.isAlive) {
        log.info("Shutting down Remote Actors Service")
        RemoteNode.shutdown
        remoteServerThread.join(1000)
    }
    Cluster.shutdown
  }
}