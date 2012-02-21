/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch

import akka.AkkaException
import java.util.{ Comparator, PriorityQueue, Queue }
import akka.util._
import akka.actor.{ ActorCell, ActorRef }
import java.util.concurrent._
import annotation.tailrec
import akka.event.Logging.Error
import akka.actor.ActorContext

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
  // since this is a compile-time constant, scalac will elide code behind if (Mailbox.debug) (RK checked with 2.9.1)
  final val debug = false
}

/**
 * Mailbox and InternalMailbox is separated in two classes because ActorCell is needed for implementation,
 * but can't be exposed to user defined mailbox subclasses.
 *
 */
private[akka] abstract class Mailbox(val actor: ActorCell, val messageQueue: MessageQueue)
  extends SystemMessageQueue with Runnable {

  import Mailbox._

  def enqueue(receiver: ActorRef, msg: Envelope): Unit = messageQueue.enqueue(receiver, msg)
  def dequeue(): Envelope = messageQueue.dequeue()
  def hasMessages: Boolean = messageQueue.hasMessages
  def numberOfMessages: Int = messageQueue.numberOfMessages

  @volatile
  protected var _statusDoNotCallMeDirectly: Status = _ //0 by default

  @volatile
  protected var _systemQueueDoNotCallMeDirectly: SystemMessage = _ //null by default

  @inline
  final def status: Mailbox.Status = Unsafe.instance.getIntVolatile(this, AbstractMailbox.mailboxStatusOffset)

  @inline
  final def shouldProcessMessage: Boolean = (status & 3) == Open

  @inline
  final def isSuspended: Boolean = (status & 3) == Suspended

  @inline
  final def isClosed: Boolean = status == Closed

  @inline
  final def isScheduled: Boolean = (status & Scheduled) != 0

  @inline
  protected final def updateStatus(oldStatus: Status, newStatus: Status): Boolean =
    Unsafe.instance.compareAndSwapInt(this, AbstractMailbox.mailboxStatusOffset, oldStatus, newStatus)

  @inline
  protected final def setStatus(newStatus: Status): Unit =
    Unsafe.instance.putIntVolatile(this, AbstractMailbox.mailboxStatusOffset, newStatus)

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

    updateStatus(s, s & ~Scheduled) || setAsIdle()
  }

  /*
   * AtomicReferenceFieldUpdater for system queue
   */
  protected final def systemQueueGet: SystemMessage =
    Unsafe.instance.getObjectVolatile(this, AbstractMailbox.systemMessageOffset).asInstanceOf[SystemMessage]
  protected final def systemQueuePut(_old: SystemMessage, _new: SystemMessage): Boolean =
    Unsafe.instance.compareAndSwapObject(this, AbstractMailbox.systemMessageOffset, _old, _new)

  final def canBeScheduledForExecution(hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = status match {
    case Open | Scheduled ⇒ hasMessageHint || hasSystemMessageHint || hasSystemMessages || hasMessages
    case Closed           ⇒ false
    case _                ⇒ hasSystemMessageHint || hasSystemMessages
  }

  final def run = {
    try {
      if (!isClosed) { //Volatile read, needed here
        processAllSystemMessages() //First, deal with any system messages
        processMailbox() //Then deal with messages
      }
    } finally {
      setAsIdle() //Volatile write, needed here
      dispatcher.registerForExecution(this, false, false)
    }
  }

  /**
   * Process the messages in the mailbox
   */
  @tailrec private final def processMailbox(
    left: Int = java.lang.Math.max(dispatcher.throughput, 1),
    deadlineNs: Long = if (dispatcher.isThroughputDeadlineTimeDefined == true) System.nanoTime + dispatcher.throughputDeadlineTime.toNanos else 0L): Unit =
    if (shouldProcessMessage) {
      val next = dequeue()
      if (next ne null) {
        if (Mailbox.debug) println(actor.self + " processing message " + next)
        actor invoke next
        processAllSystemMessages()
        if ((left > 1) && ((dispatcher.isThroughputDeadlineTimeDefined == false) || (System.nanoTime - deadlineNs) < 0))
          processMailbox(left - 1, deadlineNs)
      }
    }

  final def processAllSystemMessages() {
    var nextMessage = systemDrain()
    try {
      while ((nextMessage ne null) && !isClosed) {
        if (debug) println(actor.self + " processing system message " + nextMessage + " with " +
          (if (actor.childrenRefs.isEmpty) "no children"
          else if (actor.childrenRefs.size > 20) actor.childrenRefs.size + " children"
          else actor.childrenRefs.mkString("children:\n    ", "\n    ", "")))
        actor systemInvoke nextMessage
        nextMessage = nextMessage.next
        // don’t ever execute normal message when system message present!
        if (nextMessage eq null) nextMessage = systemDrain()
      }
    } catch {
      case NonFatal(e) ⇒
        actor.system.eventStream.publish(Error(e, actor.self.path.toString, this.getClass, "exception during processing system messages, dropping " + SystemMessage.size(nextMessage) + " messages!"))
        throw e
    }
  }

  @inline
  final def dispatcher: MessageDispatcher = actor.dispatcher

  /**
   * Overridable callback to clean up the mailbox,
   * called when an actor is unregistered.
   * By default it dequeues all system messages + messages and ships them to the owning actors' systems' DeadLetterMailbox
   */
  protected[dispatch] def cleanUp(): Unit =
    if (actor ne null) { // actor is null for the deadLetterMailbox
      val dlm = actor.systemImpl.deadLetterMailbox
      if (hasSystemMessages) {
        var message = systemDrain()
        while (message ne null) {
          // message must be “virgin” before being able to systemEnqueue again
          val next = message.next
          message.next = null
          dlm.systemEnqueue(actor.self, message)
          message = next
        }
      }

      if (messageQueue ne null) // needed for CallingThreadDispatcher, which never calls Mailbox.run()
        messageQueue.cleanUp(actor, actor.systemImpl.deadLetterQueue)
    }
}

trait MessageQueue {
  /**
   * Try to enqueue the message to this queue, or throw an exception.
   */
  def enqueue(receiver: ActorRef, handle: Envelope): Unit // NOTE: receiver is used only in two places, but cannot be removed

  /**
   * Try to dequeue the next message from this queue, return null failing that.
   */
  def dequeue(): Envelope

  /**
   * Should return the current number of messages held in this queue; may
   * always return 0 if no other value is available efficiently. Do not use
   * this for testing for presence of messages, use `hasMessages` instead.
   */
  def numberOfMessages: Int

  /**
   * Indicates whether this queue is non-empty.
   */
  def hasMessages: Boolean

  /**
   * Called when the mailbox this queue belongs to is disposed of. Normally it
   * is expected to transfer all remaining messages into the dead letter queue
   * which is passed in. The owner of this MessageQueue is passed in if
   * available (e.g. for creating DeadLetters()), “/deadletters” otherwise.
   */
  def cleanUp(owner: ActorContext, deadLetters: MessageQueue): Unit
}

/**
 * Internal mailbox implementation detail.
 */
private[akka] trait SystemMessageQueue {
  /**
   * Enqueue a new system message, e.g. by prepending atomically as new head of a single-linked list.
   */
  def systemEnqueue(receiver: ActorRef, message: SystemMessage): Unit

  /**
   * Dequeue all messages from system queue and return them as single-linked list.
   */
  def systemDrain(): SystemMessage

  def hasSystemMessages: Boolean
}

/**
 * Internal mailbox implementation detail.
 */
private[akka] trait DefaultSystemMessageQueue { self: Mailbox ⇒

  @tailrec
  final def systemEnqueue(receiver: ActorRef, message: SystemMessage): Unit = {
    assert(message.next eq null)
    if (Mailbox.debug) println(actor.self + " having enqueued " + message)
    val head = systemQueueGet
    /*
     * this write is safely published by the compareAndSet contained within
     * systemQueuePut; “Intra-Thread Semantics” on page 12 of the JSR133 spec
     * guarantees that “head” uses the value obtained from systemQueueGet above.
     * Hence, SystemMessage.next does not need to be volatile.
     */
    message.next = head
    if (!systemQueuePut(head, message)) {
      message.next = null
      systemEnqueue(receiver, message)
    }
  }

  @tailrec
  final def systemDrain(): SystemMessage = {
    val head = systemQueueGet
    if (systemQueuePut(head, null)) SystemMessage.reverse(head) else systemDrain()
  }

  def hasSystemMessages: Boolean = systemQueueGet ne null
}

trait UnboundedMessageQueueSemantics extends QueueBasedMessageQueue {
  def enqueue(receiver: ActorRef, handle: Envelope): Unit = queue add handle
  def dequeue(): Envelope = queue.poll()
}

trait BoundedMessageQueueSemantics extends QueueBasedMessageQueue {
  def pushTimeOut: Duration
  override def queue: BlockingQueue[Envelope]

  def enqueue(receiver: ActorRef, handle: Envelope) {
    if (pushTimeOut.length > 0) {
      queue.offer(handle, pushTimeOut.length, pushTimeOut.unit) || {
        throw new MessageQueueAppendFailedException("Couldn't enqueue message " + handle + " to " + receiver)
      }
    } else queue put handle
  }

  def dequeue(): Envelope = queue.poll()
}

trait QueueBasedMessageQueue extends MessageQueue {
  def queue: Queue[Envelope]
  def numberOfMessages = queue.size
  def hasMessages = !queue.isEmpty
  def cleanUp(owner: ActorContext, deadLetters: MessageQueue): Unit = {
    if (hasMessages) {
      var envelope = dequeue
      while (envelope ne null) {
        deadLetters.enqueue(owner.self, envelope)
        envelope = dequeue
      }
    }
  }
}

/**
 * Mailbox configuration.
 */
trait MailboxType {
  def create(owner: Option[ActorContext]): MessageQueue
}

/**
 * It's a case class for Java (new UnboundedMailbox)
 */
case class UnboundedMailbox() extends MailboxType {
  final override def create(owner: Option[ActorContext]): MessageQueue =
    new QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
      final val queue = new ConcurrentLinkedQueue[Envelope]()
    }
}

case class BoundedMailbox( final val capacity: Int, final val pushTimeOut: Duration) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  final override def create(owner: Option[ActorContext]): MessageQueue =
    new QueueBasedMessageQueue with BoundedMessageQueueSemantics {
      final val queue = new LinkedBlockingQueue[Envelope](capacity)
      final val pushTimeOut = BoundedMailbox.this.pushTimeOut
    }
}

case class UnboundedPriorityMailbox( final val cmp: Comparator[Envelope]) extends MailboxType {
  final override def create(owner: Option[ActorContext]): MessageQueue =
    new QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
      final val queue = new PriorityBlockingQueue[Envelope](11, cmp)
    }
}

case class BoundedPriorityMailbox( final val cmp: Comparator[Envelope], final val capacity: Int, final val pushTimeOut: Duration) extends MailboxType {

  if (capacity < 0) throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")

  final override def create(owner: Option[ActorContext]): MessageQueue =
    new QueueBasedMessageQueue with BoundedMessageQueueSemantics {
      final val queue = new BoundedBlockingQueue[Envelope](capacity, new PriorityQueue[Envelope](11, cmp))
      final val pushTimeOut = BoundedPriorityMailbox.this.pushTimeOut
    }
}

