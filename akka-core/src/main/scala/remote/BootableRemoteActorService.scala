/**
 * Copyright (C) 2009 Scalable Solutions.
 */
 
package se.scalablesolutions.akka.remote

import se.scalablesolutions.akka.actor.BootableActorLoaderService
import se.scalablesolutions.akka.util.{Bootable,Logging}

trait BootableRemoteActorService extends Bootable with Logging {
  self : BootableActorLoaderService =>
  
  import Config._
  
  protected lazy val remoteServerThread = new Thread(new Runnable() {
    def run = RemoteNode.start(self.applicationLoader)
  }, "Akka Remote Service")
  
  def startRemoteService = remoteServerThread.start
  
  abstract override def onLoad   = {
    super.onLoad
    if(config.getBool("akka.remote.server.service", true)) startRemoteService
  }
  
  abstract override def onUnload = {
  	super.onUnload
  	
  	if (remoteServerThread.isAlive) {
        log.info("Shutting down remote service")
        RemoteNode.shutdown
        remoteServerThread.join(1000)
    }
  }
}