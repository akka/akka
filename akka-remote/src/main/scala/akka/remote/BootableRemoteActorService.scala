/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor.{ Actor, BootableActorLoaderService }
import akka.util.{ ReflectiveAccess, Bootable }
import akka.event.EventHandler

/**
 * This bundle/service is responsible for booting up and shutting down the remote actors facility.
 * <p/>
 * It is used in Kernel.
 */
trait BootableRemoteActorService extends Bootable {
  self: BootableActorLoaderService ⇒

  def settings: RemoteServerSettings

  protected lazy val remoteServerThread = new Thread(new Runnable() {
    def run = app.remote.start(self.applicationLoader.getOrElse(null)) //Use config host/port
  }, "Akka RemoteModule Service")

  def startRemoteService() { remoteServerThread.start() }

  abstract override def onLoad() {
    if (app.reflective.ClusterModule.isEnabled && settings.isRemotingEnabled) {
      EventHandler.info(this, "Initializing Remote Actors Service...")
      startRemoteService()
      EventHandler.info(this, "Remote Actors Service initialized")
    }
    super.onLoad()
  }

  abstract override def onUnload() {
    EventHandler.info(this, "Shutting down Remote Actors Service")

    app.remote.shutdown()
    if (remoteServerThread.isAlive) remoteServerThread.join(1000)
    EventHandler.info(this, "Remote Actors Service has been shut down")
    super.onUnload()
  }
}
