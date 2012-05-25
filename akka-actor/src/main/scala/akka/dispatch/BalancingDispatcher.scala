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

  @deprecated("Will become private[akka] in 2.1, this is not user-api", "2.0.2")
  val team = new ConcurrentSkipListSet[ActorCell](
    Helpers.identityHashComparator(new Comparator[ActorCell] {
      def compare(l: ActorCell, r: ActorCell) = l.self.path compareTo r.self.path
    }))

  val messageQueue: MessageQueue = mailboxType.create(None)

  protected[akka] override def createMailbox(actor: ActorCell): Mailbox = new SharingMailbox(actor, messageQueue)

  protected[akka] override def register(actor: ActorCell): Unit = {
    super.register(actor)
    team.add(actor)
  }

  protected[akka] override def unregister(actor: ActorCell): Unit = {
    team.remove(actor)
    super.unregister(actor)
    teamWork()
  }

  override protected[akka] def dispatch(receiver: ActorCell, invocation: Envelope) = {
    messageQueue.enqueue(receiver.self, invocation)
    if (!registerForExecution(receiver.mailbox, false, false)) teamWork()
  }

  protected def teamWork(): Unit = if (attemptTeamWork) {
    @tailrec def scheduleOne(i: Iterator[ActorCell] = team.iterator): Unit =
      if (messageQueue.hasMessages
        && i.hasNext
        && (executorService.get().executor match {
          case lm: LoadMetrics ⇒ lm.atFullThrottle == false
          case other           ⇒ true
        })
        && !registerForExecution(i.next.mailbox, false, false))
        scheduleOne(i)

    scheduleOne()
  }
}

@deprecated("Will become private[akka] in 2.1, this is not user-api", "2.0.2")
class SharingMailbox(_actor: ActorCell, _messageQueue: MessageQueue)
  extends Mailbox(_actor, _messageQueue) with DefaultSystemMessageQueue {

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
