/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.actor.{Actor, ActorType, ActorRef, ActorInitializationException}
import akka.AkkaException

import java.util.{Queue, List, Comparator}
import java.util.concurrent._
import concurrent.forkjoin.LinkedTransferQueue
import akka.util._

class MessageQueueAppendFailedException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageQueue {
  val dispatcherLock = new SimpleLock
  val suspended = new SimpleLock
  def enqueue(handle: MessageInvocation)
  def dequeue(): MessageInvocation
  def size: Int
  def isEmpty: Boolean
}

/**
 * Mailbox configuration.
 */
sealed trait MailboxType

case class UnboundedMailbox(val blocking: Boolean = false) extends MailboxType
case class BoundedMailbox(
  val blocking: Boolean     = false,
  val capacity: Int         = { if (Dispatchers.MAILBOX_CAPACITY < 0) Int.MaxValue else Dispatchers.MAILBOX_CAPACITY },
  val pushTimeOut: Duration = Dispatchers.MAILBOX_PUSH_TIME_OUT) extends MailboxType {
  if (capacity < 0)        throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")
}

trait UnboundedMessageQueueSemantics extends MessageQueue { self: BlockingQueue[MessageInvocation] =>
  def blockDequeue: Boolean

  final def enqueue(handle: MessageInvocation) {
    this add handle
  }

  final def dequeue(): MessageInvocation = {
    if (blockDequeue) this.take()
    else this.poll()
  }
}

trait BoundedMessageQueueSemantics extends MessageQueue { self: BlockingQueue[MessageInvocation] =>
  def blockDequeue: Boolean
  def pushTimeOut: Duration

  final def enqueue(handle: MessageInvocation) {
    if (pushTimeOut.toMillis > 0) {
      if (!this.offer(handle, pushTimeOut.length, pushTimeOut.unit))
        throw new MessageQueueAppendFailedException("Couldn't enqueue message " + handle + " to " + toString)
    } else this put handle
  }

  final def dequeue(): MessageInvocation =
    if (blockDequeue) this.take()
    else this.poll()
}

class DefaultUnboundedMessageQueue(val blockDequeue: Boolean) extends
      LinkedBlockingQueue[MessageInvocation] with
      UnboundedMessageQueueSemantics

class DefaultBoundedMessageQueue(capacity: Int, val pushTimeOut: Duration, val blockDequeue: Boolean) extends
      LinkedBlockingQueue[MessageInvocation](capacity) with
      BoundedMessageQueueSemantics

class UnboundedPriorityMessageQueue(val blockDequeue: Boolean, cmp: Comparator[MessageInvocation]) extends
      PriorityBlockingQueue[MessageInvocation](11, cmp) with
      UnboundedMessageQueueSemantics

/* PriorityBlockingQueue cannot be bounded
  class BoundedPriorityMessageQueue(capacity: Int, val pushTimeOut: Duration, val blockDequeue: Boolean, cmp: Comparator[MessageInvocation]) extends
      PriorityBlockingQueue[MessageInvocation](capacity, cmp) with
      BoundedMessageQueueSemantics
      */