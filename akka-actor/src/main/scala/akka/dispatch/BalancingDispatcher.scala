/**
 *    Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.actor.{ ActorCell, ActorRef }
import annotation.tailrec
import akka.util.{ Duration, Helpers }
import java.util.{ Comparator, Iterator }
import java.util.concurrent.{ Executor, LinkedBlockingQueue, ConcurrentLinkedQueue, ConcurrentSkipListSet }

/**
 * An executor based event driven dispatcher which will try to redistribute work from busy actors to idle actors. It is assumed
 * that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors. I.e. the
 * actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.
 * <p/>
 * Although the technique used in this implementation is commonly known as "work stealing", the actual implementation is probably
 * best described as "work donating" because the actor of which work is being stolen takes the initiative.
 * <p/>
 * The preferred way of creating dispatchers is to define configuration of it and use the
 * the `lookup` method in [[akka.dispatch.Dispatchers]].
 *
 * @see akka.dispatch.BalancingDispatcher
 * @see akka.dispatch.Dispatchers
 */
class BalancingDispatcher(
  _prerequisites: DispatcherPrerequisites,
  _id: String,
  throughput: Int,
  throughputDeadlineTime: Duration,
  mailboxType: MailboxType,
  _executorServiceFactoryProvider: ExecutorServiceFactoryProvider,
  _shutdownTimeout: Duration,
  attemptTeamWork: Boolean)
  extends Dispatcher(_prerequisites, _id, throughput, throughputDeadlineTime, mailboxType, _executorServiceFactoryProvider, _shutdownTimeout) {

  val buddies = new ConcurrentSkipListSet[ActorCell](
    Helpers.identityHashComparator(new Comparator[ActorCell] {
      def compare(l: ActorCell, r: ActorCell) = l.self.path compareTo r.self.path
    }))

  val messageQueue: MessageQueue = mailboxType match {
    case UnboundedMailbox() ⇒
      new QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
        final val queue = new ConcurrentLinkedQueue[Envelope]
      }

    case BoundedMailbox(cap, timeout) ⇒
      new QueueBasedMessageQueue with BoundedMessageQueueSemantics {
        final val queue = new LinkedBlockingQueue[Envelope](cap)
        final val pushTimeOut = timeout
      }

    case other ⇒ throw new IllegalArgumentException("Only handles BoundedMailbox and UnboundedMailbox, but you specified [" + other + "]")
  }

  protected[akka] override def createMailbox(actor: ActorCell): Mailbox = new SharingMailbox(actor)

  class SharingMailbox(_actor: ActorCell) extends Mailbox(_actor) with DefaultSystemMessageQueue {
    final def enqueue(receiver: ActorRef, handle: Envelope) = messageQueue.enqueue(receiver, handle)

    final def dequeue(): Envelope = messageQueue.dequeue()

    final def numberOfMessages: Int = messageQueue.numberOfMessages

    final def hasMessages: Boolean = messageQueue.hasMessages

    override def cleanUp(): Unit = {
      //Don't call the original implementation of this since it scraps all messages, and we don't want to do that
      if (hasSystemMessages) {
        val dlq = actor.systemImpl.deadLetterMailbox
        var message = systemDrain()
        while (message ne null) {
          // message must be “virgin” before being able to systemEnqueue again
          val next = message.next
          message.next = null
          dlq.systemEnqueue(actor.self, message)
          message = next
        }
      }
    }
  }

  protected[akka] override def register(actor: ActorCell) = {
    super.register(actor)
    buddies.add(actor)
  }

  protected[akka] override def unregister(actor: ActorCell) = {
    buddies.remove(actor)
    super.unregister(actor)
    scheduleOne()
  }

  override protected[akka] def dispatch(receiver: ActorCell, invocation: Envelope) = {
    messageQueue.enqueue(receiver.self, invocation)
    registerForExecution(receiver.mailbox, false, false)
    scheduleOne()
  }

  @tailrec private def scheduleOne(i: Iterator[ActorCell] = buddies.iterator): Unit =
    if (attemptTeamWork
      && messageQueue.hasMessages
      && i.hasNext
      && (executorService.get().executor match {
        case lm: LoadMetrics ⇒ lm.atFullThrottle == false
        case other           ⇒ true
      })
      && !registerForExecution(i.next.mailbox, false, false))
      scheduleOne(i)
}