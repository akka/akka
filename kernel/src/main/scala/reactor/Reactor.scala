/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.reactor

import java.util.Queue
import kernel.stm.Transaction
import kernel.util.HashCode

trait MessageQueue {
  def append(handle: MessageInvocation)
  def prepend(handle: MessageInvocation)
}

trait MessageInvoker {
  def invoke(message: MessageInvocation)
}

trait MessageDispatcher {
  def messageQueue: MessageQueue
  def registerHandler(key: AnyRef, handler: MessageInvoker)
  def unregisterHandler(key: AnyRef)
  def start
  def shutdown
}

trait MessageDemultiplexer {
  def select
  def acquireSelectedQueue: Queue[MessageInvocation]
  def releaseSelectedQueue
  def wakeUp
}

class MessageInvocation(val sender: AnyRef,
                        val message: AnyRef,
                        val future: Option[CompletableFutureResult],
                        val tx: Option[Transaction]) {

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, sender)
    result = HashCode.hash(result, message)
    result = if (future.isDefined) HashCode.hash(result, future.get) else result
    result = if (tx.isDefined) HashCode.hash(result, tx.get.id) else result
    result
  }

  override def equals(that: Any): Boolean =
    that != null &&
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].sender == sender &&
    that.asInstanceOf[MessageInvocation].message == message &&
    that.asInstanceOf[MessageInvocation].future.isDefined == future.isDefined &&
    that.asInstanceOf[MessageInvocation].future.get == future.get &&
    that.asInstanceOf[MessageInvocation].tx.isDefined == tx.isDefined &&
    that.asInstanceOf[MessageInvocation].tx.get.id == tx.get.id

  override def toString(): String = "MessageInvocation[message = " + message + ", sender = " + sender + ", future = " + future + ", tx = " + tx + "]"
}
