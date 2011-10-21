/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.AkkaException
import java.util.{ Comparator, PriorityQueue }
import akka.util._
import java.util.Queue
import akka.actor.{ ActorContext, ActorCell }
import java.util.concurrent._
import atomic.{ AtomicInteger, AtomicReferenceFieldUpdater }
import annotation.tailrec

class MessageQueueAppendFailedException(message: String, cause: Throwable = null) extends AkkaException(message, cause)

object Mailbox {

  type Status = Int

  /*
   * the following assigned numbers CANNOT be changed without looking at the code which uses them!
   */

  // primary status: only first three
  final val Open = 0 // _status is not initialized in AbstractMailbox, so default must be zero!
  final val Suspended = 1
  final val Closed = 2
  // secondary status: Scheduled bit may be added to Open/Suspended
  final val Scheduled = 4

  // mailbox debugging helper using println (see below)
  // TODO take this out before release
  final val debug = false
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class Mailbox(val actor: ActorCell) extends AbstractMailbox with MessageQueue with SystemMessageQueue with Runnable {
  import Mailbox._

  @inline
  final def status: Mailbox.Status = AbstractMailbox.updater.get(this)

  @inline
  final def isActive: Boolean = (status & 3) == Open

  @inline
  final def isSuspended: Boolean = (status & 3) == Suspended

  @inline
  final def isClosed: Boolean = status == Closed

  @inline
  final def isScheduled: Boolean = (status & Scheduled) != 0

  @inline
  protected final def updateStatus(oldStatus: Status, newStatus: Status): Boolean =
    AbstractMailbox.updater.compareAndSet(this, oldStatus, newStatus)

  @inline
  protected final def setStatus(newStatus: Status): Unit =
    AbstractMailbox.updater.set(this, newStatus)

  /**
   * Internal method to enforce a volatile write of the status
   */
  @tailrec
  final def acknowledgeStatus() {
    val s = status
    if (updateStatus(s, s)) ()
    else acknowledgeStatus()
  }

  /**
   * set new primary status Open. Caller does not need to worry about whether
   * status was Scheduled or not.
   */
  @tailrec
  final def becomeOpen(): Boolean = status match {
    case Closed ⇒ setStatus(Closed); false
    case s      ⇒ updateStatus(s, Open | s & Scheduled) || becomeOpen()
  }

  /**
   * set new primary status Suspended. Caller does not need to worry about whether
   * status was Scheduled or not.
   */
  @tailrec
  final def becomeSuspended(): Boolean = status match {
    case Closed ⇒ setStatus(Closed); false
    case s      ⇒ updateStatus(s, Suspended | s & Scheduled) || becomeSuspended()
  }

  /**
   * set new primary status Closed. Caller does not need to worry about whether
   * status was Scheduled or not.
   */
  @tailrec
  final def becomeClosed(): Boolean = status match {
    case Closed ⇒ setStatus(Closed); false
    case s      ⇒ updateStatus(s, Closed) || becomeClosed()
  }

  /**
   * Set Scheduled status, keeping primary status as is.
   */
  @tailrec
  final def setAsScheduled(): Boolean = {
    val s = status
    /*
     * only try to add Scheduled bit if pure Open/Suspended, not Closed or with
     * Scheduled bit already set (this is one of the reasons why the numbers
     * cannot be changed in object Mailbox above)
     */
    if (s <= Suspended) updateStatus(s, s | Scheduled) || setAsScheduled()
    else false
  }

  /**
   * Reset Scheduled status, keeping primary status as is.
   */
  @tailrec
  final def setAsIdle(): Boolean = {
    val s = status
    /*
     * only try to remove Scheduled bit if currently Scheduled, not Closed or
     * without Scheduled bit set (this is one of the reasons why the numbers
     * cannot be changed in object Mailbox above)
     */
    if (s >= Scheduled) {
      updateStatus(s, s & ~Scheduled) || setAsIdle()
    } else {
      acknowledgeStatus() // this write is needed to make memory consistent after processMailbox()
      false
    }
  }

  /*
   * AtomicReferenceFieldUpdater for system queue
   */
  protected final def systemQueueGet: SystemMessage = AbstractMailbox.systemQueueUpdater.get(this)
  protected final def systemQueuePut(_old: SystemMessage, _new: SystemMessage): Boolean = AbstractMailbox.systemQueueUpdater.compareAndSet(this, _old, _new)

  def shouldBeRegisteredForExecution(hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = status match {
    case Open | Scheduled ⇒ hasMessageHint || hasSystemMessageHint || hasSystemMessages || hasMessages
    case Closed           ⇒ false
    case _                ⇒ hasSystemMessageHint || hasSystemMessages
  }

  final def run = {
    try processMailbox()
    finally {
      setAsIdle()
      dispatcher.registerForExecution(this, false, false)
    }
  }

  /**
   * Process the messages in the mailbox
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  final def processMailbox() {
    processAllSystemMessages() //First, process all system messages

    if (isActive) {
      var nextMessage = dequeue()
      if (nextMessage ne null) { //If we have a message
        if (dispatcher.isThroughputDefined) { //If we're using throughput, we need to do some book-keeping
          var processedMessages = 0
          val deadlineNs = if (dispatcher.isThroughputDeadlineTimeDefined) System.nanoTime + TimeUnit.MILLISECONDS.toNanos(dispatcher.throughputDeadlineTime) else 0
          do {
            if (debug) println(actor + " processing message " + nextMessage)
            actor invoke nextMessage

            processAllSystemMessages() //After we're done, process all system messages

            nextMessage = if (isActive) { // If we aren't suspended, we need to make sure we're not overstepping our boundaries
              processedMessages += 1
              if ((processedMessages >= dispatcher.throughput) || (dispatcher.isThroughputDeadlineTimeDefined && System.nanoTime >= deadlineNs)) // If we're throttled, break out
                null //We reached our boundaries, abort
              else dequeue //Dequeue the next message
            } else null //Abort
          } while (nextMessage ne null)
        } else { //If we only run one message per process
          actor invoke nextMessage //Just run it
          processAllSystemMessages() //After we're done, process all system messages
        }
      }
    }
  }

  def processAllSystemMessages() {
    var nextMessage = systemDrain()
    try {
      while (nextMessage ne null) {
        if (debug) println(actor + " processing system message " + nextMessage)
        actor systemInvoke nextMessage
        nextMessage = nextMessage.next
        // don’t ever execute normal message when system message present!
        if (nextMessage eq null) nextMessage = systemDrain()
      }
    } catch {
      case e ⇒
        actor.app.eventHandler.error(e, this, "exception during processing system messages, dropping " + SystemMessage.size(nextMessage) + " messages!")
        throw e
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
  /**
   * Enqueue a new system message, e.g. by prepending atomically as new head of a single-linked list.
   */
  def systemEnqueue(message: SystemMessage): Unit

  /**
   * Dequeue all messages from system queue and return them as single-linked list.
   */
  def systemDrain(): SystemMessage

  def hasSystemMessages: Boolean
}

trait DefaultSystemMessageQueue { self: Mailbox ⇒

  @tailrec
  final def systemEnqueue(message: SystemMessage): Unit = {
    if (Mailbox.debug) println(actor + " having enqueued " + message)
    val head = systemQueueGet
    /*
     * this write is safely published by the compareAndSet contained within
     * systemQueuePut; “Intra-Thread Semantics” on page 12 of the JSR133 spec
     * guarantees that “head” uses the value obtained from systemQueueGet above.
     * Hence, SystemMessage.next does not need to be volatile.
     */
    message.next = head
    if (!systemQueuePut(head, message)) systemEnqueue(message)
  }

  @tailrec
  final def systemDrain(): SystemMessage = {
    val head = systemQueueGet
    if (systemQueuePut(head, null)) SystemMessage.reverse(head) else systemDrain()
  }

  def hasSystemMessages: Boolean = systemQueueGet ne null
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
  def create(dispatcher: MessageDispatcher, receiver: ActorCell): Mailbox
}

/**
 * It's a case class for Java (new UnboundedMailbox)
 */
case class UnboundedMailbox() extends MailboxType {
  override def create(_dispatcher: MessageDispatcher, receiver: ActorCell) =
    new Mailbox(receiver) with QueueBasedMessageQueue with UnboundedMessageQueueSemantics with DefaultSystemMessageQueue {
      final val queue = new ConcurrentLinkedQueue[Envelope]()
      final val dispatcher = _dispatcher
    }
}

case class BoundedMailbox( final val capacity: Int, final val pushTimeOut: Duration) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  override def create(_dispatcher: MessageDispatcher, receiver: ActorCell) =
    new Mailbox(receiver) with QueueBasedMessageQueue with BoundedMessageQueueSemantics with DefaultSystemMessageQueue {
      final val queue = new LinkedBlockingQueue[Envelope](capacity)
      final val pushTimeOut = BoundedMailbox.this.pushTimeOut
      final val dispatcher = _dispatcher
    }
}

case class UnboundedPriorityMailbox( final val cmp: Comparator[Envelope]) extends MailboxType {
  override def create(_dispatcher: MessageDispatcher, receiver: ActorCell) =
    new Mailbox(receiver) with QueueBasedMessageQueue with UnboundedMessageQueueSemantics with DefaultSystemMessageQueue {
      final val queue = new PriorityBlockingQueue[Envelope](11, cmp)
      final val dispatcher = _dispatcher
    }
}

case class BoundedPriorityMailbox( final val cmp: Comparator[Envelope], final val capacity: Int, final val pushTimeOut: Duration) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  override def create(_dispatcher: MessageDispatcher, receiver: ActorCell) =
    new Mailbox(receiver) with QueueBasedMessageQueue with BoundedMessageQueueSemantics with DefaultSystemMessageQueue {
      final val queue = new BoundedBlockingQueue[Envelope](capacity, new PriorityQueue[Envelope](11, cmp))
      final val pushTimeOut = BoundedPriorityMailbox.this.pushTimeOut
      final val dispatcher = _dispatcher
    }
}

