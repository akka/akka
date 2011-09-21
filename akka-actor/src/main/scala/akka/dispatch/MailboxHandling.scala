/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.AkkaException
import java.util.{ Comparator, PriorityQueue }
import akka.util._
import java.util.Queue
import akka.actor.ActorContext
import java.util.concurrent._

class MessageQueueAppendFailedException(message: String, cause: Throwable = null) extends AkkaException(message, cause)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Mailbox extends Runnable {
  /*
   * Internal implementation of MessageDispatcher uses these, don't touch or rely on
   */
  final val dispatcherLock = new SimpleLock(startLocked = false)
  final val suspended = new SimpleLock(startLocked = false) //(startLocked = true)

  final def run = {
    try { processMailbox() } catch {
      case ie: InterruptedException ⇒ Thread.currentThread().interrupt() //Restore interrupt
    } finally {
      dispatcherLock.unlock()
      if (hasMessages || hasSystemMessages)
        dispatcher.reRegisterForExecution(this)
    }
  }

  /**
   * Process the messages in the mailbox
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  final def processMailbox() {
    if (processAllSystemMessages() && !suspended.locked) {
      var nextMessage = dequeue()
      if (nextMessage ne null) { //If we have a message
        if (dispatcher.throughput <= 1) //If we only run one message per process
          nextMessage.invoke //Just run it
        else { //But otherwise, if we are throttled, we need to do some book-keeping
          var processedMessages = 0
          val isDeadlineEnabled = dispatcher.throughputDeadlineTime > 0
          val deadlineNs = if (isDeadlineEnabled) System.nanoTime + TimeUnit.MILLISECONDS.toNanos(dispatcher.throughputDeadlineTime)
          else 0
          do {
            nextMessage.invoke
            nextMessage =
              if (!processAllSystemMessages() || suspended.locked) {
                null // If we are suspended, abort
              } else { // If we aren't suspended, we need to make sure we're not overstepping our boundaries
                processedMessages += 1
                if ((processedMessages >= dispatcher.throughput) || (isDeadlineEnabled && System.nanoTime >= deadlineNs)) // If we're throttled, break out
                  null //We reached our boundaries, abort
                else dequeue //Dequeue the next message
              }
          } while (nextMessage ne null)
        }
      }
    }
  }

  def processAllSystemMessages(): Boolean = {
    var nextMessage = systemDequeue()
    while (nextMessage ne null) {
      if (!nextMessage.invoke()) return false
      nextMessage = systemDequeue()
    }
    true
  }

  /*
   * These method need to be implemented in subclasses; they should not rely on the internal stuff above.
   */
  def enqueue(handle: Envelope)
  def dequeue(): Envelope

  def numberOfMessages: Int

  def systemEnqueue(handle: SystemEnvelope): Unit
  def systemDequeue(): SystemEnvelope

  def hasMessages: Boolean
  def hasSystemMessages: Boolean

  def dispatcher: MessageDispatcher
}

trait DefaultSystemMessageImpl { self: Mailbox ⇒
  val systemMessages = new ConcurrentLinkedQueue[SystemEnvelope]()

  def systemEnqueue(handle: SystemEnvelope): Unit = systemMessages offer handle
  def systemDequeue(): SystemEnvelope = systemMessages.poll()

  def hasSystemMessages: Boolean = !systemMessages.isEmpty
}

trait UnboundedMessageQueueSemantics { self: QueueMailbox ⇒
  val queue: Queue[Envelope]

  final def enqueue(handle: Envelope): Unit = queue add handle
  final def dequeue(): Envelope = queue.poll()
}

trait BoundedMessageQueueSemantics { self: BlockingQueueMailbox ⇒
  def pushTimeOut: Duration

  final def enqueue(handle: Envelope) {
    if (pushTimeOut.length > 0) {
      queue.offer(handle, pushTimeOut.length, pushTimeOut.unit) || {
        throw new MessageQueueAppendFailedException("Couldn't enqueue message " + handle + " to " + toString)
      }
    } else queue put handle
  }

  final def dequeue(): Envelope = queue.poll()
}

trait QueueMailbox extends Mailbox {
  val queue: Queue[Envelope]
  final def numberOfMessages = queue.size
  final def hasMessages = !queue.isEmpty
}

trait BlockingQueueMailbox extends QueueMailbox {
  val queue: BlockingQueue[Envelope]
}

abstract class PriorityBlockingQueueMailbox(cmp: Comparator[Envelope], val dispatcher: MessageDispatcher) extends BlockingQueueMailbox {
  override val queue = new PriorityBlockingQueue(11, cmp) // 11 is the default initial capacity in PriorityQueue.java
}

abstract class ConcurrentLinkedQueueMailbox(val dispatcher: MessageDispatcher) extends QueueMailbox {
  override val queue = new ConcurrentLinkedQueue[Envelope]
}

abstract class LinkedBlockingQueueMailbox(val dispatcher: MessageDispatcher) extends BlockingQueueMailbox {
  override val queue = new LinkedBlockingQueue[Envelope]
}

/**
 * Mailbox configuration.
 */
trait MailboxType {
  def create(dispatcher: MessageDispatcher): Mailbox
}

case class UnboundedMailbox() extends MailboxType {
  override def create(dispatcher: MessageDispatcher) = new ConcurrentLinkedQueueMailbox(dispatcher) with UnboundedMessageQueueSemantics with DefaultSystemMessageImpl
}

case class BoundedMailbox(
  val capacity: Int = { if (Dispatchers.MAILBOX_CAPACITY < 0) Int.MaxValue else Dispatchers.MAILBOX_CAPACITY },
  val pushTimeOut: Duration = Dispatchers.MAILBOX_PUSH_TIME_OUT) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  override def create(dispatcher: MessageDispatcher) = new LinkedBlockingQueueMailbox(dispatcher) with BoundedMessageQueueSemantics with DefaultSystemMessageImpl {
    val capacity = BoundedMailbox.this.capacity
    val pushTimeOut = BoundedMailbox.this.pushTimeOut
  }
}

case class UnboundedPriorityMailbox(cmp: Comparator[Envelope]) extends MailboxType {
  override def create(dispatcher: MessageDispatcher) = new PriorityBlockingQueueMailbox(cmp, dispatcher) with UnboundedMessageQueueSemantics with DefaultSystemMessageImpl
}

case class BoundedPriorityMailbox(
  val cmp: Comparator[Envelope],
  val capacity: Int = { if (Dispatchers.MAILBOX_CAPACITY < 0) Int.MaxValue else Dispatchers.MAILBOX_CAPACITY },
  val pushTimeOut: Duration = Dispatchers.MAILBOX_PUSH_TIME_OUT) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  override def create(dispatcher: MessageDispatcher) = new PriorityBlockingQueueMailbox(cmp, dispatcher) with BoundedMessageQueueSemantics with DefaultSystemMessageImpl {
    val capacity = BoundedPriorityMailbox.this.capacity
    val pushTimeOut = BoundedPriorityMailbox.this.pushTimeOut
  }
}

