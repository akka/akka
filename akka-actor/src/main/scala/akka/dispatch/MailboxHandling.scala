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
import atomic.{ AtomicInteger, AtomicReferenceFieldUpdater }
import annotation.tailrec

class MessageQueueAppendFailedException(message: String, cause: Throwable = null) extends AkkaException(message, cause)

object Mailbox {
  type Status = Int
  val Open = 0
  val Suspended = 1
  val Closed = 2
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
  val _status: AtomicInteger = new AtomicInteger(Open) //Must be named _status because of the updater

  final def status: Mailbox.Status = _status.get() //mailboxStatusUpdater.get(this)

  final def isSuspended: Boolean = status == Suspended
  final def isClosed: Boolean = status == Closed
  final def isOpen: Boolean = status == Open

  /**
   * Internal method to enforce a volatile write of the status
   */
  @tailrec
  final def acknowledgeStatus() {
    val s = _status.get()
    if (_status.compareAndSet(s, s)) ()
    else acknowledgeStatus()
  }

  def become(newStatus: Status): Boolean = {
    @tailrec
    def transcend(): Boolean = {
      val current = _status.get()
      if (current == Closed) { _status.set(Closed); false } //Ensure that the write is always performed
      else if (_status.compareAndSet(current, newStatus)) true
      else transcend()
    }
    transcend()
  } //mailboxStatusUpdater.set(this, newStatus)

  def shouldBeRegisteredForExecution(hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = status match {
    case `Open`      ⇒ hasMessageHint || hasSystemMessageHint || hasSystemMessages || hasMessages
    case `Closed`    ⇒ false
    case `Suspended` ⇒ hasSystemMessageHint || hasSystemMessages
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

    if (isOpen) {
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

            nextMessage = if (isOpen) { // If we aren't suspended, we need to make sure we're not overstepping our boundaries
              processedMessages += 1
              if ((processedMessages >= dispatcher.throughput) || (isDeadlineEnabled && System.nanoTime >= deadlineNs)) // If we're throttled, break out
                null //We reached our boundaries, abort
              else dequeue //Dequeue the next message
            } else null //Abort
          } while (nextMessage ne null)
        }
      }
    }
  }

  def processAllSystemMessages() {
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

