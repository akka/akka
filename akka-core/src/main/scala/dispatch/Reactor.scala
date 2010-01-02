/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.List

import se.scalablesolutions.akka.util.{HashCode, Logging}
import se.scalablesolutions.akka.stm.Transaction
import se.scalablesolutions.akka.actor.Actor

import java.util.concurrent.ConcurrentHashMap

final class MessageInvocation(val receiver: Actor,
                              val message: Any,
                              val future: Option[CompletableFutureResult],
                              val sender: Option[Actor],
                              val tx: Option[Transaction]) {
  if (receiver eq null) throw new IllegalArgumentException("receiver is null")

  def invoke = receiver.invoke(this)

  def send = receiver.dispatcher.dispatch(this)

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, receiver)
    result = HashCode.hash(result, message.asInstanceOf[AnyRef])
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].receiver == receiver &&
    that.asInstanceOf[MessageInvocation].message == message
  }

  override def toString(): String = synchronized {
    "MessageInvocation[" +
     "\n\tmessage = " + message +
     "\n\treceiver = " + receiver +
     "\n\tsender = " + sender +
     "\n\tfuture = " + future +
     "\n\ttx = " + tx +
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
  protected val references = new ConcurrentHashMap[String, Actor]  
  def dispatch(invocation: MessageInvocation)
  def start
  def shutdown
  def register(actor: Actor) = references.put(actor.uuid, actor)
  def unregister(actor: Actor) = references.remove(actor.uuid)
  def canBeShutDown: Boolean = references.isEmpty
  def isShutdown: Boolean
}

trait MessageDemultiplexer {
  def select
  def wakeUp
  def acquireSelectedInvocations: List[MessageInvocation]
  def releaseSelectedInvocations
}
