/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.event.Logging.Warning
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ TimeUnit, ExecutorService, RejectedExecutionException, ConcurrentLinkedQueue }
import akka.actor.{ ActorCell, ActorKilledException }
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.actor.Scheduler

/**
 * Default settings are:
 * <pre/>
 *   - withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
 *   - NR_START_THREADS = 16
 *   - NR_MAX_THREADS = 128
 *   - KEEP_ALIVE_TIME = 60000L // one minute
 * </pre>
 * <p/>
 *
 * The dispatcher has a fluent builder interface to build up a thread pool to suite your use-case.
 * There is a default thread pool defined but make use of the builder if you need it. Here are some examples.
 * <p/>
 *
 * Scala API.
 * <p/>
 * Example usage:
 * <pre/>
 *   val dispatcher = new Dispatcher("name")
 *   dispatcher
 *     .withNewThreadPoolWithBoundedBlockingQueue(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTimeInMillis(60000)
 *     .buildThreadPool
 * </pre>
 * <p/>
 *
 * Java API.
 * <p/>
 * Example usage:
 * <pre/>
 *   Dispatcher dispatcher = new Dispatcher("name");
 *   dispatcher
 *     .withNewThreadPoolWithBoundedBlockingQueue(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTimeInMillis(60000)
 *     .buildThreadPool();
 * </pre>
 * <p/>
 *
 * But the preferred way of creating dispatchers is to use
 * the {@link akka.dispatch.Dispatchers} factory object.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 * @param throughput positive integer indicates the dispatcher will only process so much messages at a time from the
 *                   mailbox, without checking the mailboxes of other actors. Zero or negative means the dispatcher
 *                   always continues until the mailbox is empty.
 *                   Larger values (or zero or negative) increase throughput, smaller values increase fairness
 */
class Dispatcher(
  _prerequisites: DispatcherPrerequisites,
  val name: String,
  val throughput: Int,
  val throughputDeadlineTime: Int,
  val mailboxType: MailboxType,
  executorServiceFactoryProvider: ExecutorServiceFactoryProvider,
  val timeoutMs: Long)
  extends MessageDispatcher(_prerequisites) {

  protected[akka] val executorServiceFactory = executorServiceFactoryProvider.createExecutorServiceFactory(name)
  protected[akka] val executorService = new AtomicReference[ExecutorService](new ExecutorServiceDelegate {
    lazy val executor = executorServiceFactory.createExecutorService
  })

  protected[akka] def dispatch(receiver: ActorCell, invocation: Envelope) = {
    val mbox = receiver.mailbox
    mbox.enqueue(receiver.self, invocation)
    registerForExecution(mbox, true, false)
  }

  protected[akka] def systemDispatch(receiver: ActorCell, invocation: SystemMessage) = {
    val mbox = receiver.mailbox
    mbox.systemEnqueue(receiver.self, invocation)
    registerForExecution(mbox, false, true)
  }

  protected[akka] def executeTask(invocation: TaskInvocation) {
    try {
      executorService.get() execute invocation
    } catch {
      case e: RejectedExecutionException ⇒
        try {
          executorService.get() execute invocation
        } catch {
          case e2: RejectedExecutionException ⇒
            prerequisites.eventStream.publish(Warning("Dispatcher", e2.toString))
            throw e2
        }
    }
  }

  protected[akka] def createMailbox(actor: ActorCell): Mailbox = mailboxType.create(actor)

  protected[akka] def shutdown {
    executorService.getAndSet(new ExecutorServiceDelegate {
      lazy val executor = executorServiceFactory.createExecutorService
    }) match {
      case null ⇒
      case some ⇒ some.shutdown()
    }
  }

  /**
   * Returns if it was registered
   */
  protected[akka] override def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = {
    if (mbox.shouldBeScheduledForExecution(hasMessageHint, hasSystemMessageHint)) { //This needs to be here to ensure thread safety and no races
      if (mbox.setAsScheduled()) {
        try {
          executorService.get() execute mbox
          true
        } catch {
          case e: RejectedExecutionException ⇒
            try {
              executorService.get() execute mbox
              true
            } catch { //Retry once
              case e: RejectedExecutionException ⇒ mbox.setAsIdle(); throw e
            }
        }
      } else false
    } else false
  }

  override val toString = getClass.getSimpleName + "[" + name + "]"
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
