/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.reactor

import java.util.List
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
  def acquireSelectedInvocations: List[MessageInvocation]
  def releaseSelectedInvocations
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
    result
  }

  override def equals(that: Any): Boolean =
    that != null &&
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].sender == sender &&
    that.asInstanceOf[MessageInvocation].message == message

  override def toString(): String = "MessageInvocation[message = " + message + ", sender = " + sender + ", future = " + future + ", tx = " + tx + "]"
}
