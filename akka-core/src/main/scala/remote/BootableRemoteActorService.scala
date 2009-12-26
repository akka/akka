/**
 * Copyright (C) 2009 Scalable Solutions.
 */
 
package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.util.{Bootable,Logging}

/**
 * This bundle/service is responsible for booting up and shutting down the remote actors facility
 * It's used in Kernel
 */

trait BootableRemoteActorService extends Bootable with Logging {
  self : BootableActorLoaderService =>
  
  import Config._
  
  protected lazy val remoteServerThread = new Thread(new Runnable() {
    def run = RemoteNode.start(self.applicationLoader)
  }, "Akka Remote Service")
  
  def startRemoteService = remoteServerThread.start
  
  abstract override def onLoad   = {
    super.onLoad //Make sure the actors facility is loaded before we load the remote service
    if(config.getBool("akka.remote.server.service", true)){
      log.info("Initializing Remote Actors Service...")
      startRemoteService
      log.info("Remote Actors Service initialized!")
    }
  }
  
  abstract override def onUnload = {
  	super.onUnload
  	
  	if (remoteServerThread.isAlive) {
        log.info("Shutting down Remote Actors Service")
        RemoteNode.shutdown
        remoteServerThread.join(1000)
    }
  }
}