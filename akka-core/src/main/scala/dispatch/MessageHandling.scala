/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.List

import se.scalablesolutions.akka.util.{HashCode, Logging}
import se.scalablesolutions.akka.actor.{Actor, ActorRef}

import java.util.concurrent.ConcurrentHashMap

import org.multiverse.commitbarriers.CountDownCommitBarrier

final class MessageInvocation(val receiver: ActorRef,
                              val message: Any,
                              val sender: Option[ActorRef],
                              val senderFuture: Option[CompletableFuture[Any]],
                              val transactionSet: Option[CountDownCommitBarrier]) {
  if (receiver eq null) throw new IllegalArgumentException("receiver is null")

  def invoke = receiver.invoke(this)

  def send = receiver.dispatcher.dispatch(this)

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, receiver.actor)
    result = HashCode.hash(result, message.asInstanceOf[AnyRef])
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].receiver.actor == receiver.actor &&
    that.asInstanceOf[MessageInvocation].message == message
  }

  override def toString = synchronized {
    "MessageInvocation[" +
     "\n\tmessage = " + message +
     "\n\treceiver = " + receiver +
     "\n\tsender = " + sender +
     "\n\tsenderFuture = " + senderFuture +
     "\n\ttransactionSet = " + transactionSet +
     "\n]"
  }
}

trait MessageQueue {
  def append(handle: MessageInvocation)
}

trait MessageInvoker {
  def invoke(message: MessageInvocation)
}

trait MessageDispatcher extends Logging {
  protected val references = new ConcurrentHashMap[String, ActorRef]
  def dispatch(invocation: MessageInvocation)
  def start
  def shutdown
  def register(actorRef: ActorRef) = references.put(actorRef.uuid, actorRef)
  def unregister(actorRef: ActorRef) = {
    references.remove(actorRef.uuid)
    if (canBeShutDown)
      shutdown // shut down in the dispatcher's references is zero
  }
  def canBeShutDown: Boolean = references.isEmpty
  def isShutdown: Boolean
  def usesActorMailbox : Boolean
}

trait MessageDemultiplexer {
  def select
  def wakeUp
  def acquireSelectedInvocations: List[MessageInvocation]
  def releaseSelectedInvocations
}
