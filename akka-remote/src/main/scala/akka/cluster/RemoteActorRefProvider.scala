/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor._
import DeploymentConfig._
import Actor._
import Status._
import akka.event.EventHandler
import akka.AkkaException
import RemoteProtocol._

import java.net.InetSocketAddress

/**
 * Remote ActorRefProvider.
 */
class RemoteActorRefProvider extends ActorRefProvider {

  def actorOf(props: Props, address: String): Option[ActorRef] = {
    Address.validate(address)

    val actorRef = Actor.remote.actors.get(address)
    if (actorRef ne null) Some(actorRef)
    else {
      // if 'Props.deployId' is not specified then use 'address' as 'deployId'
      val deployId = props.deployId match {
        case Props.`defaultDeployId` | null ⇒ address
        case other                          ⇒ other
      }

      Deployer.lookupDeploymentFor(deployId) match {
        case Some(Deploy(_, _, router, _, RemoteConfig(host, port))) ⇒
          // FIXME create RoutedActorRef if 'router' is specified

          val inetSocketAddress = null
          Some(createRemoteActorRef(address, inetSocketAddress)) // create a remote actor

        case deploy ⇒ None // non-remote actor
      }
    }
  }

  def findActorRef(address: String): Option[ActorRef] = throw new UnsupportedOperationException

  private def createRemoteActorRef(actorAddress: String, inetSocketAddress: InetSocketAddress) = {
    RemoteActorRef(inetSocketAddress, actorAddress, Actor.TIMEOUT, None)
  }

  private def sendCommandToConnection(
    connection: ActorRef,
    command: RemoteDaemonMessageProtocol,
    async: Boolean = true) {

    if (async) {
      connection ! command
    } else {
      try {
        (connection ? (command, Remote.remoteDaemonAckTimeout)).as[Status] match {
          case Some(Success(status)) ⇒
            EventHandler.debug(this, "Remote command sent to [%s] successfully received".format(status))

          case Some(Failure(cause)) ⇒
            EventHandler.error(cause, this, cause.toString)
            throw cause

          case None ⇒
            val error = new RemoteException("Remote command to [%s] timed out".format(connection.address))
            EventHandler.error(error, this, error.toString)
            throw error
        }
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, "Could not send remote command to [%s] due to: %s".format(connection.address, e.toString))
          throw e
      }
    }
  }
}
