/**
 *    Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import util.DynamicVariable
import akka.actor.{ ActorCell, Actor, IllegalActorStateException }
import java.util.concurrent.{ LinkedBlockingQueue, ConcurrentLinkedQueue, ConcurrentSkipListSet }
import java.util.{ Comparator, Queue }
import annotation.tailrec
import akka.AkkaApplication

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
  _app: AkkaApplication,
  _name: String,
  throughput: Int,
  throughputDeadlineTime: Int,
  mailboxType: MailboxType,
  config: ThreadPoolConfig,
  _timeoutMs: Long)
  extends Dispatcher(_app, _name, throughput, throughputDeadlineTime, mailboxType, config, _timeoutMs) {

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

  class SharingMailbox(_actor: ActorCell) extends Mailbox(_actor) with DefaultSystemMessageQueue {
    final def enqueue(handle: Envelope) = messageQueue.enqueue(handle)

    final def dequeue(): Envelope = messageQueue.dequeue()

    final def numberOfMessages: Int = messageQueue.numberOfMessages

    final def hasMessages: Boolean = messageQueue.hasMessages

    final val dispatcher = BalancingDispatcher.this
  }

  protected[akka] override def register(actor: ActorCell) = {
    super.register(actor)
    registerForExecution(actor.mailbox, false, false) //Allow newcomers to be productive from the first moment
  }

  protected[akka] override def unregister(actor: ActorCell) = {
    super.unregister(actor)
    buddies.remove(actor)
    val buddy = buddies.pollFirst()
    if (buddy ne null)
      registerForExecution(buddy.mailbox, false, false)
  }

  protected[akka] override def shutdown() {
    super.shutdown()
    buddies.clear()
  }

  protected override def cleanUpMailboxFor(actor: ActorCell, mailBox: Mailbox) {
    if (mailBox.hasSystemMessages) {
      var messages = mailBox.systemDrain()
      while (messages ne null) {
        deadLetterMailbox.systemEnqueue(messages) //Send to dead letter queue
        messages = messages.next
        if (messages eq null) //Make sure that any system messages received after the current drain are also sent to the dead letter mbox
          messages = mailBox.systemDrain()
      }
    }
  }

  protected[akka] override def registerForExecution(mbox: Mailbox, hasMessagesHint: Boolean, hasSystemMessagesHint: Boolean): Boolean = {
    if (!super.registerForExecution(mbox, hasMessagesHint, hasSystemMessagesHint)) {
      if (mbox.isInstanceOf[SharingMailbox]) buddies.add(mbox.asInstanceOf[SharingMailbox].actor)
      false
    } else true
  }

  override protected[akka] def dispatch(receiver: ActorCell, invocation: Envelope) = {
    messageQueue enqueue invocation

    val buddy = buddies.pollFirst()
    if ((buddy ne null) && (buddy ne receiver))
      registerForExecution(buddy.mailbox, false, false)

    registerForExecution(receiver.mailbox, false, false)
  }
}
