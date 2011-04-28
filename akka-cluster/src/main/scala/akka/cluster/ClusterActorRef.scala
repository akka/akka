/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import Cluster._

import akka.actor._
import akka.actor.Actor._
import akka.event.EventHandler
import akka.dispatch.CompletableFuture

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.eaio.uuid.UUID

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ClusterActorRef private[akka] (
  actorAddresses: Array[Tuple2[UUID, InetSocketAddress]],
  val serviceId: String,
  actorClassName: String,
  timeout: Long,
  actorType: ActorType,
  val replicationStrategy: ReplicationStrategy)
  extends RemoteActorRef(serviceId, actorClassName, timeout, None, actorType) {
  this: ClusterActorRef with Router.Router =>

  EventHandler.debug(this, "Creating a ClusterActorRef [%s] for Actor [%s]".format(serviceId, actorClassName))

  private[akka] val addresses = new AtomicReference[Map[InetSocketAddress, ActorRef]](
    createConnections(actorAddresses, actorClassName))

  def connections: Map[InetSocketAddress, ActorRef] = addresses.get.toMap

  override def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit =
    route(message)(senderOption)

  override def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] =
    route[T](message, timeout)(senderOption).asInstanceOf[CompletableFuture[T]]

  private[akka] def failOver(from: InetSocketAddress, to: InetSocketAddress) {
    addresses set (
      addresses.get map { case (address, actorRef) =>
        if (address == from) {
          actorRef.stop
          (to, createRemoteActorRef(actorRef.uuid, to))
        } else (address, actorRef)
      }
    )
  }

  private def createConnections(
    addresses: Array[Tuple2[UUID, InetSocketAddress]],
    actorClassName: String): Map[InetSocketAddress, ActorRef] = {
    var connections = Map.empty[InetSocketAddress, ActorRef]
    addresses foreach { case (uuid, address) =>
      connections = connections + (address -> createRemoteActorRef(uuid, address))
    }
    connections
  }

  private def createRemoteActorRef(uuid: UUID, address: InetSocketAddress) = {
    RemoteActorRef(
      UUID_PREFIX + uuidToString(uuid), actorClassName, // clustered refs are always registered and looked up by UUID
      Actor.TIMEOUT, None, actorType)
  }
}
