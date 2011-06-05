/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster

import Cluster._

import akka.actor._
import Actor._
import akka.dispatch._
import akka.util._
import ReflectiveAccess._
import ClusterModule._
import akka.event.EventHandler
import akka.dispatch.Future

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.{ Map ⇒ JMap }

import com.eaio.uuid.UUID

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ClusterActorRef private[akka] (
  inetSocketAddresses: Array[Tuple2[UUID, InetSocketAddress]],
  val address: String,
  _timeout: Long)
  extends ActorRef with ScalaActorRef { this: Router.Router ⇒

  timeout = _timeout

  private[akka] val inetSocketAddressToActorRefMap = new AtomicReference[Map[InetSocketAddress, ActorRef]](
    (Map[InetSocketAddress, ActorRef]() /: inetSocketAddresses) {
      case (map, (uuid, inetSocketAddress)) ⇒ map + (inetSocketAddress -> createRemoteActorRef(address, inetSocketAddress))
    })

  ClusterModule.ensureEnabled()
  start()

  def connections: Map[InetSocketAddress, ActorRef] = inetSocketAddressToActorRefMap.get

  override def postMessageToMailbox(message: Any, channel: UntypedChannel): Unit = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    route(message)(sender)
  }

  override def postMessageToMailboxAndCreateFutureResultWithTimeout(
    message: Any,
    timeout: Long,
    channel: UntypedChannel): Future[Any] = {
    val sender = channel match {
      case ref: ActorRef ⇒ Some(ref)
      case _             ⇒ None
    }
    route[Any](message, timeout)(sender)
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

  def start(): this.type = synchronized[this.type] {
    _status = ActorRefInternals.RUNNING
    this
  }

  def stop() {
    synchronized {
      if (_status == ActorRefInternals.RUNNING) {
        _status = ActorRefInternals.SHUTDOWN
        postMessageToMailbox(RemoteActorSystemMessage.Stop, None)
      }
    }
  }

  // ==== NOT SUPPORTED ====
  // FIXME move these methods and the same ones in RemoteActorRef to a base class - now duplicated
  def dispatcher_=(md: MessageDispatcher) {
    unsupported
  }
  def dispatcher: MessageDispatcher = unsupported
  def link(actorRef: ActorRef) {
    unsupported
  }
  def unlink(actorRef: ActorRef) {
    unsupported
  }
  def startLink(actorRef: ActorRef): ActorRef = unsupported
  def supervisor: Option[ActorRef] = unsupported
  def linkedActors: JMap[Uuid, ActorRef] = unsupported
  protected[akka] def mailbox: AnyRef = unsupported
  protected[akka] def mailbox_=(value: AnyRef): AnyRef = unsupported
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable) {
    unsupported
  }
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    unsupported
  }
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    unsupported
  }
  protected[akka] def invoke(messageHandle: MessageInvocation) {
    unsupported
  }
  protected[akka] def supervisor_=(sup: Option[ActorRef]) {
    unsupported
  }
  protected[akka] def actorInstance: AtomicReference[Actor] = unsupported
  private def unsupported = throw new UnsupportedOperationException("Not supported for RemoteActorRef")
}
