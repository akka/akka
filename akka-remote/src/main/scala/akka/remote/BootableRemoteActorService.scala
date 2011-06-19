/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.remote

import akka.actor.{ Actor, BootableActorLoaderService }
import akka.util.{ ReflectiveAccess, Bootable }

/**
 * This bundle/service is responsible for booting up and shutting down the remote actors facility
 * <p/>
 * It is used in Kernel
 */
trait BootableRemoteActorService extends Bootable {
  self: BootableActorLoaderService ⇒

  protected lazy val remoteServerThread = new Thread(new Runnable() {
    def run = Actor.remote.start(self.applicationLoader.getOrElse(null)) //Use config host/port
  }, "Akka Remote Service")

  def startRemoteService() { remoteServerThread.start() }

  abstract override def onLoad() {
    if (ReflectiveAccess.isRemotingEnabled && RemoteServerSettings.isRemotingEnabled) {
      startRemoteService()
    }
    super.onLoad()
  }

  abstract override def onUnload() {
    Actor.remote.shutdown()
    if (remoteServerThread.isAlive) remoteServerThread.join(1000)
    super.onUnload()
  }
}
