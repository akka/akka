package akka.cluster

/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
import Cluster._

import akka.actor._
import akka.remote.MessageSerializer
import akka.event.EventHandler
import akka.config.Supervision._
import akka.dispatch._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.{ Map ⇒ JMap }

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Replicable { this: Actor ⇒
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed trait ReplicationStrategy

object ReplicationStrategy {
  case object Transient extends ReplicationStrategy
  case object WriteThrough extends ReplicationStrategy
  case object WriteBehind extends ReplicationStrategy
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ReplicatedActorRef private[akka] (actorRef: ActorRef, val address: String) extends ActorRef with ScalaActorRef {

  private lazy val txLog = {
    EventHandler.debug(this, "Creating a ReplicatedActorRef for Actor [%s]".format(address))
    TransactionLog.newLogFor(uuid.toString)
  }

  def invoke(messageHandle: MessageInvocation) {
    actorRef.invoke(messageHandle)
    txLog.recordEntry(MessageSerializer.serialize(messageHandle.message).toByteArray)
  }

  def start(): ActorRef = {
    EventHandler.debug(this, "Starting ReplicatedActorRef for Actor [%s] with transaction log [%s]"
      .format(address, txLog.logId))
    actorRef.start()
  }

  def stop() {
    txLog.delete()
    actorRef.stop()
  }

  override def setFaultHandler(handler: FaultHandlingStrategy) {
    actorRef.setFaultHandler(handler)
  }
  override def getFaultHandler: FaultHandlingStrategy = actorRef.getFaultHandler()
  override def setLifeCycle(lifeCycle: LifeCycle) {
    actorRef.setLifeCycle(lifeCycle)
  }
  override def getLifeCycle: LifeCycle = actorRef.getLifeCycle
  def dispatcher_=(md: MessageDispatcher) {
    actorRef.dispatcher_=(md)
  }
  def dispatcher: MessageDispatcher = actorRef.dispatcher
  def link(actorRef: ActorRef) {
    actorRef.link(actorRef)
  }
  def unlink(actorRef: ActorRef) {
    actorRef.unlink(actorRef)
  }
  def startLink(actorRef: ActorRef): ActorRef = actorRef.startLink(actorRef)
  def supervisor: Option[ActorRef] = actorRef.supervisor
  def linkedActors: JMap[Uuid, ActorRef] = actorRef.linkedActors
  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]) {
    actorRef.postMessageToMailbox(message, senderOption)
  }
  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[Promise[T]]): Promise[T] = actorRef.postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, senderOption, senderFuture)
  protected[akka] def actorInstance: AtomicReference[Actor] = actorRef.actorInstance
  protected[akka] def supervisor_=(sup: Option[ActorRef]) {
    actorRef.supervisor_=(sup)
  }
  protected[akka] def mailbox: AnyRef = actorRef.mailbox
  protected[akka] def mailbox_=(value: AnyRef): AnyRef = actorRef.mailbox_=(value)
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable) {
    actorRef.handleTrapExit(dead, reason)
  }
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    actorRef.restart(reason, maxNrOfRetries, withinTimeRange)
  }
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]) {
    actorRef.restartLinkedActors(reason, maxNrOfRetries, withinTimeRange)
  }
}
