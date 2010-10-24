/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{ActorRegistry, ActorRef, Uuid, ActorInitializationException}

import org.multiverse.commitbarriers.CountDownCommitBarrier

import java.util.concurrent._
import se.scalablesolutions.akka.util. {ReentrantGuard, Logging, HashCode}

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
  protected val guard = new ReentrantGuard

  final def attach(actorRef: ActorRef): Unit = guard withGuard {
    register(actorRef)
  }

  final def detach(actorRef: ActorRef): Unit = guard withGuard {
    unregister(actorRef)
  }

  protected def register(actorRef: ActorRef) {
    if (uuids.isEmpty()) start
    if (actorRef.mailbox eq null) actorRef.mailbox = createMailbox(actorRef)
    uuids add actorRef.uuid
  }
  
  protected def unregister(actorRef: ActorRef) = {
    if (uuids remove actorRef.uuid) {
      actorRef.mailbox = null
      if (uuids.isEmpty) shutdown // shut down in the dispatcher's references is zero
    }
  }

  def stopAllLinkedActors {
    val i = uuids.iterator
    while(i.hasNext()) {
      val uuid = i.next()
      ActorRegistry.actorFor(uuid) match {
        case Some(actor) => actor.stop
        case None => log.warn("stopAllLinkedActors couldn't find linked actor: " + uuid)
      }
    }
    if(uuids.isEmpty) shutdown
  }
  
  def suspend(actorRef: ActorRef): Unit
  def resume(actorRef: ActorRef): Unit

  def dispatch(invocation: MessageInvocation): Unit

  protected def start: Unit
  protected def shutdown: Unit

  /**
   * Returns the size of the mailbox for the specified actor
   */
  def mailboxSize(actorRef: ActorRef): Int
}