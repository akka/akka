/**
 *    Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import util.DynamicVariable
import akka.actor.{ ActorCell, Actor, IllegalActorStateException }
import java.util.concurrent.{ LinkedBlockingQueue, ConcurrentLinkedQueue, ConcurrentSkipListSet }
import java.util.{ Comparator, Queue }
import annotation.tailrec

/**
 * An executor based event driven dispatcher which will try to redistribute work from busy actors to idle actors. It is assumed
 * that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors. I.e. the
 * actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.
 * <p/>
 * Although the technique used in this implementation is commonly known as "work stealing", the actual implementation is probably
 * best described as "work donating" because the actor of which work is being stolen takes the initiative.
 * <p/>
 * The preferred way of creating dispatchers is to use
 * the {@link akka.dispatch.Dispatchers} factory object.
 *
 * @see akka.dispatch.BalancingDispatcher
 * @see akka.dispatch.Dispatchers
 *
 * @author Viktor Klang
 */
class BalancingDispatcher(
  _name: String,
  throughput: Int = Dispatchers.THROUGHPUT,
  throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  config: ThreadPoolConfig = ThreadPoolConfig())
  extends Dispatcher(_name, throughput, throughputDeadlineTime, mailboxType, config) {

  def this(_name: String, throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(_name, throughput, throughputDeadlineTime, mailboxType, ThreadPoolConfig()) // Needed for Java API usage

  def this(_name: String, throughput: Int, mailboxType: MailboxType) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(_name: String, throughput: Int) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String, _config: ThreadPoolConfig) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, _config)

  def this(_name: String, memberType: Class[_ <: Actor]) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String, mailboxType: MailboxType) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  private val buddies = new ConcurrentSkipListSet[ActorCell](new Comparator[ActorCell] { def compare(a: ActorCell, b: ActorCell) = a.uuid.compareTo(b.uuid) }) //new ConcurrentLinkedQueue[ActorCell]()

  protected val messageQueue: MessageQueue = mailboxType match {
    case u: UnboundedMailbox ⇒ new QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
      final val queue = new ConcurrentLinkedQueue[Envelope]
      final val dispatcher = BalancingDispatcher.this
    }
    case BoundedMailbox(cap, timeout) ⇒ new QueueBasedMessageQueue with BoundedMessageQueueSemantics {
      final val queue = new LinkedBlockingQueue[Envelope](cap)
      final val dispatcher = BalancingDispatcher.this
      final val pushTimeOut = timeout
    }
    case other ⇒ throw new IllegalArgumentException("Only handles BoundedMailbox and UnboundedMailbox, but you specified [" + other + "]")
  }

  protected[akka] override def createMailbox(actor: ActorCell): Mailbox = new SharingMailbox(actor)

  class SharingMailbox(val actor: ActorCell) extends Mailbox with DefaultSystemMessageQueue {
    final def enqueue(handle: Envelope) = messageQueue.enqueue(handle)

    final def dequeue(): Envelope = {
      val envelope = messageQueue.dequeue()
      if (envelope eq null) null
      else if (envelope.receiver eq actor) envelope
      else envelope.copy(receiver = actor)
    }

    final def numberOfMessages: Int = messageQueue.numberOfMessages

    final def hasMessages: Boolean = messageQueue.hasMessages

    final val dispatcher = BalancingDispatcher.this
  }

  protected[akka] override def register(actor: ActorCell) = {
    super.register(actor)
    registerForExecution(actor.mailbox, false, false)
  }

  protected[akka] override def unregister(actor: ActorCell) = {
    super.unregister(actor)
    buddies.remove(actor)
  }

  protected override def cleanUpMailboxFor(actor: ActorCell, mailBox: Mailbox) {
    if (mailBox.hasSystemMessages) {
      var envelope = mailBox.systemDequeue()
      while (envelope ne null) {
        deadLetterMailbox.systemEnqueue(envelope) //Send to dead letter queue
        envelope = mailBox.systemDequeue()
      }
    }
  }

  protected[akka] override def registerForExecution(mbox: Mailbox, hasMessagesHint: Boolean, hasSystemMessagesHint: Boolean): Boolean = {
    if (!super.registerForExecution(mbox, hasMessagesHint, hasSystemMessagesHint)) {
      if (!mbox.isClosed && mbox.isInstanceOf[SharingMailbox]) buddies.add(mbox.asInstanceOf[SharingMailbox].actor)
      false
    } else true
  }

  override protected[akka] def dispatch(invocation: Envelope) = {
    val receiver = invocation.receiver
    messageQueue enqueue invocation

    @tailrec
    def getValidBuddy(): ActorCell = buddies.pollFirst() match {
      case null | `receiver`             ⇒ receiver
      case buddy if buddy.mailbox.isOpen ⇒ buddy
      case _                             ⇒ getValidBuddy
    }

    registerForExecution(getValidBuddy().mailbox, true, false)
  }
}
