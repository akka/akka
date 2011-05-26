/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import Cluster._

import akka.actor._
import akka.actor.Actor._
import akka.event.EventHandler
import akka.dispatch.Promise

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.eaio.uuid.UUID

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ClusterActorRef private[akka] (
  inetSocketAddresses: Array[Tuple2[UUID, InetSocketAddress]],
  actorAddress: String,
  timeout: Long,
  val replicationStrategy: ReplicationStrategy)
  extends RemoteActorRef(null, actorAddress, timeout, None) { // FIXME UGLY HACK - should not extend RemoteActorRef
  this: ClusterActorRef with Router.Router ⇒

  EventHandler.debug(this,
    "Creating a ClusterActorRef for actor with address [%s] with connections [\n\t%s]"
      .format(actorAddress, inetSocketAddresses.mkString("\n\t")))

  private[akka] val inetSocketAddressToActorRefMap = new AtomicReference[Map[InetSocketAddress, ActorRef]](
    (Map[InetSocketAddress, ActorRef]() /: inetSocketAddresses) {
      case (map, (uuid, inetSocketAddress)) ⇒ map + (inetSocketAddress -> createRemoteActorRef(actorAddress, inetSocketAddress))
    })

  def connections: Map[InetSocketAddress, ActorRef] = inetSocketAddressToActorRefMap.get

  override def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit =
    route(message)(senderOption)

  override def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[Promise[T]]): Promise[T] = {
    route[T](message, timeout)(senderOption).asInstanceOf[Promise[T]]
  }

  private[akka] def failOver(fromInetSocketAddress: InetSocketAddress, toInetSocketAddress: InetSocketAddress) {
    inetSocketAddressToActorRefMap set (inetSocketAddressToActorRefMap.get map {
      case (`fromInetSocketAddress`, actorRef) ⇒
        actorRef.stop()
        (toInetSocketAddress, createRemoteActorRef(actorRef.address, toInetSocketAddress))
      case other ⇒ other
    })
  }

  private def createRemoteActorRef(actorAddress: String, inetSocketAddress: InetSocketAddress) = {
    RemoteActorRef(inetSocketAddress, actorAddress, Actor.TIMEOUT, None)
  }
}
