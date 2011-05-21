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
  address: String,
  timeout: Long,
  val replicationStrategy: ReplicationStrategy)
  extends RemoteActorRef(address, timeout, None) {
  this: ClusterActorRef with Router.Router ⇒

  EventHandler.debug(this, "Creating a ClusterActorRef for actor with address [%s]".format(address))

  private[akka] val addresses = new AtomicReference[Map[InetSocketAddress, ActorRef]](
    (Map[InetSocketAddress, ActorRef]() /: actorAddresses) {
      case (map, (uuid, address)) ⇒ map + (address -> createRemoteActorRef(uuid, address))
    })

  def connections: Map[InetSocketAddress, ActorRef] = addresses.get

  override def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit =
    route(message)(senderOption)

  override def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] =
    route[T](message, timeout)(senderOption).asInstanceOf[CompletableFuture[T]]

  private[akka] def failOver(from: InetSocketAddress, to: InetSocketAddress) {
    addresses set (addresses.get map {
      case (`from`, actorRef) ⇒
        actorRef.stop()
        (to, createRemoteActorRef(actorRef.uuid, to))
      case other ⇒ other
    })
  }

  // clustered refs are always registered and looked up by UUID
  private def createRemoteActorRef(uuid: UUID, address: InetSocketAddress) =
    RemoteActorRef(UUID_PREFIX + uuidToString(uuid), Actor.TIMEOUT, None)
}
