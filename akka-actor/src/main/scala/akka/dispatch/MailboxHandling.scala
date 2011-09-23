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
import atomic.AtomicReferenceFieldUpdater

class MessageQueueAppendFailedException(message: String, cause: Throwable = null) extends AkkaException(message, cause)

object Mailbox {
  sealed trait Status
  case object OPEN extends Status
  case object SUSPENDED extends Status
  case object CLOSED extends Status

  //private[Mailbox] val mailboxStatusUpdater = AtomicReferenceFieldUpdater.newUpdater[Mailbox, Status](classOf[Mailbox], classOf[Status], "_status")
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class Mailbox extends MessageQueue with SystemMessageQueue with Runnable {
  import Mailbox._
  /*
   * Internal implementation of MessageDispatcher uses these, don't touch or rely on
   */
  final val dispatcherLock = new SimpleLock(startLocked = false)
  @volatile
  var _status: Status = OPEN //Must be named _status because of the updater

  final def status: Mailbox.Status = _status //mailboxStatusUpdater.get(this)

  final def isSuspended: Boolean = status == SUSPENDED
  final def isClosed: Boolean = status == CLOSED
  final def isOpen: Boolean = status == OPEN

  def become(newStatus: Status) = _status = newStatus //mailboxStatusUpdater.set(this, newStatus)

  def shouldBeRegisteredForExecution(hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = status match {
    case CLOSED    ⇒ false
    case OPEN      ⇒ hasMessageHint || hasSystemMessages || hasMessages
    case SUSPENDED ⇒ hasSystemMessageHint || hasSystemMessages
  }

  final def run = {
    try { processMailbox() } catch {
      case ie: InterruptedException ⇒ Thread.currentThread().interrupt() //Restore interrupt
    } finally {
      dispatcherLock.unlock()
      dispatcher.registerForExecution(this, false, false)
    }
  }

  /**
   * Process the messages in the mailbox
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  final def processMailbox() {
    processAllSystemMessages()

    if (status == OPEN) {
      var nextMessage = dequeue()
      if (nextMessage ne null) { //If we have a message
        if (dispatcher.throughput <= 1) { //If we only run one message per process {
          nextMessage.invoke //Just run it
          processAllSystemMessages()
        } else { //But otherwise, if we are throttled, we need to do some book-keeping
          var processedMessages = 0
          val isDeadlineEnabled = dispatcher.throughputDeadlineTime > 0
          val deadlineNs = if (isDeadlineEnabled) System.nanoTime + TimeUnit.MILLISECONDS.toNanos(dispatcher.throughputDeadlineTime)
          else 0
          do {
            nextMessage.invoke

            processAllSystemMessages()

            nextMessage = if (status != OPEN) {
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

  def processAllSystemMessages(): Unit = {
    var nextMessage = systemDequeue()
    while (nextMessage ne null) {
      nextMessage.invoke()
      nextMessage = systemDequeue()
    }
  }

  def dispatcher: MessageDispatcher
}

trait MessageQueue {
  /*
   * These method need to be implemented in subclasses; they should not rely on the internal stuff above.
   */
  def enqueue(handle: Envelope)

  def dequeue(): Envelope

  def numberOfMessages: Int

  def hasMessages: Boolean
}

trait SystemMessageQueue {
  def systemEnqueue(handle: SystemEnvelope): Unit

  def systemDequeue(): SystemEnvelope

  def hasSystemMessages: Boolean
}

trait DefaultSystemMessageQueue { self: SystemMessageQueue ⇒
  val systemMessages = new ConcurrentLinkedQueue[SystemEnvelope]()

  def systemEnqueue(handle: SystemEnvelope): Unit = systemMessages offer handle

  def systemDequeue(): SystemEnvelope = systemMessages.poll()

  def hasSystemMessages: Boolean = !systemMessages.isEmpty
}

trait UnboundedMessageQueueSemantics extends QueueBasedMessageQueue {
  final def enqueue(handle: Envelope): Unit = queue add handle
  final def dequeue(): Envelope = queue.poll()
}

trait BoundedMessageQueueSemantics extends QueueBasedMessageQueue {
  def pushTimeOut: Duration
  override def queue: BlockingQueue[Envelope]

  final def enqueue(handle: Envelope) {
    if (pushTimeOut.length > 0) {
      queue.offer(handle, pushTimeOut.length, pushTimeOut.unit) || {
        throw new MessageQueueAppendFailedException("Couldn't enqueue message " + handle + " to " + toString)
      }
    } else queue put handle
  }

  final def dequeue(): Envelope = queue.poll()
}

trait QueueBasedMessageQueue extends MessageQueue {
  def queue: Queue[Envelope]
  final def numberOfMessages = queue.size
  final def hasMessages = !queue.isEmpty
}

/**
 * Mailbox configuration.
 */
trait MailboxType {
  def create(dispatcher: MessageDispatcher): Mailbox
}

/**
 * It's a case class for Java (new UnboundedMailbox)
 */
case class UnboundedMailbox() extends MailboxType {
  override def create(_dispatcher: MessageDispatcher) = new Mailbox with QueueBasedMessageQueue with UnboundedMessageQueueSemantics with DefaultSystemMessageQueue {
    final val queue = new ConcurrentLinkedQueue[Envelope]()
    final val dispatcher = _dispatcher
  }
}

case class BoundedMailbox(
  val capacity: Int = { if (Dispatchers.MAILBOX_CAPACITY < 0) Int.MaxValue else Dispatchers.MAILBOX_CAPACITY },
  val pushTimeOut: Duration = Dispatchers.MAILBOX_PUSH_TIME_OUT) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  override def create(_dispatcher: MessageDispatcher) = new Mailbox with QueueBasedMessageQueue with BoundedMessageQueueSemantics with DefaultSystemMessageQueue {
    final val queue = new LinkedBlockingQueue[Envelope](capacity)
    final val pushTimeOut = BoundedMailbox.this.pushTimeOut
    final val dispatcher = _dispatcher
  }
}

case class UnboundedPriorityMailbox(cmp: Comparator[Envelope]) extends MailboxType {
  override def create(_dispatcher: MessageDispatcher) = new Mailbox with QueueBasedMessageQueue with UnboundedMessageQueueSemantics with DefaultSystemMessageQueue {
    val queue = new PriorityBlockingQueue[Envelope](11, cmp)
    final val dispatcher = _dispatcher
  }
}

case class BoundedPriorityMailbox(
  val cmp: Comparator[Envelope],
  val capacity: Int = { if (Dispatchers.MAILBOX_CAPACITY < 0) Int.MaxValue else Dispatchers.MAILBOX_CAPACITY },
  val pushTimeOut: Duration = Dispatchers.MAILBOX_PUSH_TIME_OUT) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  override def create(_dispatcher: MessageDispatcher) = new Mailbox with QueueBasedMessageQueue with BoundedMessageQueueSemantics with DefaultSystemMessageQueue {
    final val queue = new BoundedBlockingQueue[Envelope](capacity, new PriorityQueue[Envelope](11, cmp))
    final val pushTimeOut = BoundedPriorityMailbox.this.pushTimeOut
    final val dispatcher = _dispatcher
  }
}

