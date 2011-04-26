/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cloud.cluster

import Cluster._

import akka.actor._
import akka.remote.MessageSerializer
import akka.event.EventHandler
import akka.config.Supervision._
import akka.dispatch._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.{ Map => JMap }

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Replicable { this: Actor =>
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
class ReplicatedActorRef private[akka] (actorRef: ActorRef) extends ActorRef with ScalaActorRef {

  private lazy val txLog = {
    EventHandler.debug(this, "Creating a ReplicatedActorRef for Actor [%s] on [%s]"
                             .format(actorClassName, homeAddress))
    TransactionLog.newLogFor(uuid.toString)
  }

  def invoke(messageHandle: MessageInvocation) {
    actorRef.invoke(messageHandle)
    txLog.recordEntry(MessageSerializer.serialize(messageHandle.message).toByteArray)
  }

  def start(): ActorRef = {
    EventHandler.debug(this, "Starting ReplicatedActorRef for Actor [%s] with transaction log [%s]"
                             .format(actorClassName, txLog.logId))
    actorRef.start
  }

  def stop() {
    txLog.delete()
    actorRef.stop()
  }

  override def setFaultHandler(handler: FaultHandlingStrategy) = actorRef.setFaultHandler(handler)
  override def getFaultHandler(): FaultHandlingStrategy = actorRef.getFaultHandler()
  override def setLifeCycle(lifeCycle: LifeCycle): Unit = actorRef.setLifeCycle(lifeCycle)
  override def getLifeCycle(): LifeCycle = actorRef.getLifeCycle
  def homeAddress: Option[InetSocketAddress] = actorRef.homeAddress
  def actorClass: Class[_ <: Actor] = actorRef.actorClass
  def actorClassName: String = actorRef.actorClassName
  def dispatcher_=(md: MessageDispatcher): Unit = actorRef.dispatcher_=(md)
  def dispatcher: MessageDispatcher = actorRef.dispatcher
  def link(actorRef: ActorRef): Unit = actorRef.link(actorRef)
  def unlink(actorRef: ActorRef): Unit = actorRef.unlink(actorRef)
  def startLink(actorRef: ActorRef): Unit = actorRef.startLink(actorRef)
  def spawn(clazz: Class[_ <: Actor]): ActorRef = actorRef.spawn(clazz)
  def spawnRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef = actorRef.spawnRemote(clazz, hostname, port, timeout)
  def spawnLink(clazz: Class[_ <: Actor]): ActorRef = actorRef.spawnLink(clazz)
  def spawnLinkRemote(clazz: Class[_ <: Actor], hostname: String, port: Int, timeout: Long): ActorRef = actorRef.spawnLinkRemote(clazz, hostname, port, timeout)
  def supervisor: Option[ActorRef] = actorRef.supervisor
  def linkedActors: JMap[Uuid, ActorRef] = actorRef.linkedActors
  protected[akka] def postMessageToMailbox(message: Any, senderOption: Option[ActorRef]): Unit = actorRef.postMessageToMailbox(message, senderOption)
  protected[akka] def postMessageToMailboxAndCreateFutureResultWithTimeout[T](
    message: Any,
    timeout: Long,
    senderOption: Option[ActorRef],
    senderFuture: Option[CompletableFuture[T]]): CompletableFuture[T] = actorRef.postMessageToMailboxAndCreateFutureResultWithTimeout(message, timeout, senderOption, senderFuture)
  protected[akka] def actorInstance: AtomicReference[Actor] = actorRef.actorInstance
  protected[akka] def supervisor_=(sup: Option[ActorRef]): Unit = actorRef.supervisor_=(sup)
  protected[akka] def mailbox: AnyRef = actorRef.mailbox
  protected[akka] def mailbox_=(value: AnyRef): AnyRef = actorRef.mailbox_=(value)
  protected[akka] def handleTrapExit(dead: ActorRef, reason: Throwable): Unit = actorRef.handleTrapExit(dead, reason)
  protected[akka] def restart(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = actorRef.restart(reason, maxNrOfRetries, withinTimeRange)
  protected[akka] def restartLinkedActors(reason: Throwable, maxNrOfRetries: Option[Int], withinTimeRange: Option[Int]): Unit = actorRef.restartLinkedActors(reason, maxNrOfRetries, withinTimeRange)
  protected[akka] def registerSupervisorAsRemoteActor: Option[Uuid] = actorRef.registerSupervisorAsRemoteActor
}
