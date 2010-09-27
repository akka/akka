/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{Actor, ActorRef, Uuid, ActorInitializationException}
import se.scalablesolutions.akka.util.{SimpleLock, Duration, HashCode, Logging}
import se.scalablesolutions.akka.util.ReflectiveAccess.EnterpriseModule
import se.scalablesolutions.akka.AkkaException

import org.multiverse.commitbarriers.CountDownCommitBarrier

import java.util.{Queue, List}
import java.util.concurrent._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class MessageInvocation(val receiver: ActorRef,
                              val message: Any,
                              val sender: Option[ActorRef],
                              val senderFuture: Option[CompletableFuture[Any]],
                              val transactionSet: Option[CountDownCommitBarrier]) {
  if (receiver eq null) throw new IllegalArgumentException("Receiver can't be null")

  def invoke = try {
    receiver.invoke(this)
  } catch {
    case e: NullPointerException => throw new ActorInitializationException(
      "Don't call 'self ! message' in the Actor's constructor (in Scala this means in the body of the class).")
  }

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, receiver.actor)
    result = HashCode.hash(result, message.asInstanceOf[AnyRef])
    result
  }

  override def equals(that: Any): Boolean = {
    that != null &&
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].receiver.actor == receiver.actor &&
    that.asInstanceOf[MessageInvocation].message == message
  }

  override def toString = {
    "MessageInvocation[" +
     "\n\tmessage = " + message +
     "\n\treceiver = " + receiver +
     "\n\tsender = " + sender +
     "\n\tsenderFuture = " + senderFuture +
     "\n\ttransactionSet = " + transactionSet +
     "]"
  }
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDispatcher extends MailboxFactory with Logging {
  
  protected val uuids = new ConcurrentSkipListSet[Uuid]
  
  def dispatch(invocation: MessageInvocation): Unit

  def execute(task: Runnable): Unit

  def start: Unit

  def shutdown: Unit

  def register(actorRef: ActorRef) {
    if (actorRef.mailbox eq null) actorRef.mailbox = createMailbox(actorRef)
    uuids add actorRef.uuid
  }
  
  def unregister(actorRef: ActorRef) = {
    uuids remove actorRef.uuid
    actorRef.mailbox = null
    if (canBeShutDown) shutdown // shut down in the dispatcher's references is zero
  }
  
  def canBeShutDown: Boolean = uuids.isEmpty

  def isShutdown: Boolean

  /**
   * Returns the size of the mailbox for the specified actor
   */
  def mailboxSize(actorRef: ActorRef): Int
}