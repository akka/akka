/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.event.Logging.Error
import akka.actor.ActorCell
import akka.event.Logging
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ExecutorService, RejectedExecutionException }
import scala.concurrent.forkjoin.ForkJoinPool
import scala.concurrent.duration.Duration
import scala.concurrent.Awaitable
import scala.concurrent.duration.FiniteDuration

/**
 * The event-based ``Dispatcher`` binds a set of Actors to a thread pool backed up by a
 * `BlockingQueue`.
 *
 * The preferred way of creating dispatchers is to define configuration of it and use the
 * the `lookup` method in [[akka.dispatch.Dispatchers]].
 *
 * @param throughput positive integer indicates the dispatcher will only process so much messages at a time from the
 *                   mailbox, without checking the mailboxes of other actors. Zero or negative means the dispatcher
 *                   always continues until the mailbox is empty.
 *                   Larger values (or zero or negative) increase throughput, smaller values increase fairness
 */
class Dispatcher(
  _prerequisites: DispatcherPrerequisites,
  val id: String,
  val throughput: Int,
  val throughputDeadlineTime: Duration,
  val mailboxType: MailboxType,
  executorServiceFactoryProvider: ExecutorServiceFactoryProvider,
  val shutdownTimeout: FiniteDuration)
  extends MessageDispatcher(_prerequisites) {

  private class LazyExecutorServiceDelegate(factory: ExecutorServiceFactory) extends ExecutorServiceDelegate {
    lazy val executor: ExecutorService = factory.createExecutorService
    def copy(): LazyExecutorServiceDelegate = new LazyExecutorServiceDelegate(factory)
  }

  @volatile private var executorServiceDelegate: LazyExecutorServiceDelegate =
    new LazyExecutorServiceDelegate(executorServiceFactoryProvider.createExecutorServiceFactory(id, prerequisites.threadFactory))

  protected final def executorService: ExecutorServiceDelegate = executorServiceDelegate

  /**
   * INTERNAL USE ONLY
   */
  protected[akka] def dispatch(receiver: ActorCell, invocation: Envelope): Unit = {
    val mbox = receiver.mailbox
    mbox.enqueue(receiver.self, invocation)
    registerForExecution(mbox, true, false)
  }

  /**
   * INTERNAL USE ONLY
   */
  protected[akka] def systemDispatch(receiver: ActorCell, invocation: SystemMessage): Unit = {
    val mbox = receiver.mailbox
    mbox.systemEnqueue(receiver.self, invocation)
    registerForExecution(mbox, false, true)
  }

  /**
   * INTERNAL USE ONLY
   */
  protected[akka] def executeTask(invocation: TaskInvocation) {
    try {
      executorService execute invocation
    } catch {
      case e: RejectedExecutionException ⇒
        try {
          executorService execute invocation
        } catch {
          case e2: RejectedExecutionException ⇒
            prerequisites.eventStream.publish(Error(e, getClass.getName, getClass, "executeTask was rejected twice!"))
            throw e2
        }
    }
  }

  /**
   * INTERNAL USE ONLY
   */
  protected[akka] def createMailbox(actor: akka.actor.Cell): Mailbox =
    new Mailbox(mailboxType.create(Some(actor.self), Some(actor.system))) with DefaultSystemMessageQueue

  /**
   * INTERNAL USE ONLY
   */
  protected[akka] def shutdown: Unit = {
    val newDelegate = executorServiceDelegate.copy() // Doesn't matter which one we copy
    val es = synchronized {
      val service = executorServiceDelegate
      executorServiceDelegate = newDelegate // just a quick getAndSet
      service
    }
    es.shutdown()
  }

  /**
   * Returns if it was registered
   *
   * INTERNAL USE ONLY
   */
  protected[akka] override def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = {
    if (mbox.canBeScheduledForExecution(hasMessageHint, hasSystemMessageHint)) { //This needs to be here to ensure thread safety and no races
      if (mbox.setAsScheduled()) {
        try {
          executorService execute mbox
          true
        } catch {
          case e: RejectedExecutionException ⇒
            try {
              executorService execute mbox
              true
            } catch { //Retry once
              case e: RejectedExecutionException ⇒
                mbox.setAsIdle()
                prerequisites.eventStream.publish(Error(e, getClass.getName, getClass, "registerForExecution was rejected twice!"))
                throw e
            }
        }
      } else false
    } else false
  }

  override val toString: String = Logging.simpleName(this) + "[" + id + "]"
}

object PriorityGenerator {
  /**
   * Creates a PriorityGenerator that uses the supplied function as priority generator
   */
  def apply(priorityFunction: Any ⇒ Int): PriorityGenerator = new PriorityGenerator {
    def gen(message: Any): Int = priorityFunction(message)
  }
}

/**
 * A PriorityGenerator is a convenience API to create a Comparator that orders the messages of a
 * PriorityDispatcher
 */
abstract class PriorityGenerator extends java.util.Comparator[Envelope] {
  def gen(message: Any): Int

  final def compare(thisMessage: Envelope, thatMessage: Envelope): Int =
    gen(thisMessage.message) - gen(thatMessage.message)
}
