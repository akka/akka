/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.event.EventHandler
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ TimeUnit, ExecutorService, RejectedExecutionException, ConcurrentLinkedQueue }
import akka.actor.{ ActorCell, ActorKilledException }

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
 *     .setRejectionPolicy(new CallerRunsPolicy)
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
 *     .setRejectionPolicy(new CallerRunsPolicy())
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
  _name: String,
  val throughput: Int = Dispatchers.THROUGHPUT,
  val throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  val mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  executorServiceFactoryProvider: ExecutorServiceFactoryProvider = ThreadPoolConfig())
  extends MessageDispatcher {

  def this(_name: String, throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(_name, throughput, throughputDeadlineTime, mailboxType, ThreadPoolConfig()) // Needed for Java API usage

  def this(_name: String, throughput: Int, mailboxType: MailboxType) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(_name: String, throughput: Int) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String, _executorServiceFactoryProvider: ExecutorServiceFactoryProvider) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, _executorServiceFactoryProvider)

  def this(_name: String) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  val name = "akka:event-driven:dispatcher:" + _name

  protected[akka] val executorServiceFactory = executorServiceFactoryProvider.createExecutorServiceFactory(name)
  protected[akka] val executorService = new AtomicReference[ExecutorService](new LazyExecutorServiceWrapper(executorServiceFactory.createExecutorService))

  protected[akka] def dispatch(invocation: Envelope) = {
    val mbox = invocation.receiver.mailbox
    if (mbox ne null) {
      mbox enqueue invocation
      registerForExecution(mbox)
    }
  }

  protected[akka] def systemDispatch(invocation: SystemEnvelope) = {
    val mbox = invocation.receiver.mailbox
    if (mbox ne null) {
      mbox systemEnqueue invocation
      registerForExecution(mbox)
    }
  }

  protected[akka] def executeTask(invocation: TaskInvocation): Unit = if (active.isOn) {
    try executorService.get() execute invocation
    catch {
      case e: RejectedExecutionException ⇒
        EventHandler.warning(this, e.toString)
        throw e
    }
  }

  def createMailbox(actor: ActorCell): Mailbox = mailboxType.create(this)

  protected[akka] def start {}

  protected[akka] def shutdown {
    val old = executorService.getAndSet(new LazyExecutorServiceWrapper(executorServiceFactory.createExecutorService))
    if (old ne null) {
      old.shutdownNow()
    }
  }

  protected[akka] def registerForExecution(mbox: Mailbox): Unit = {
    if (mbox.dispatcherLock.tryLock()) {
      if (active.isOn && (!mbox.suspended.locked || mbox.hasSystemMessages)) { //If the dispatcher is active and the actor not suspended
        try {
          executorService.get() execute mbox
        } catch {
          case e: RejectedExecutionException ⇒
            EventHandler.warning(this, e.toString)
            mbox.dispatcherLock.unlock()
            throw e
        }
      } else {
        mbox.dispatcherLock.unlock() //If the dispatcher isn't active or if the actor is suspended, unlock the dispatcher lock
      }
    }
  }

  protected[akka] def reRegisterForExecution(mbox: Mailbox): Unit =
    registerForExecution(mbox)

  protected override def cleanUpMailboxFor(actor: ActorCell) {
    val m = actor.mailbox
    if (m.hasMessages) {
      var invocation = m.dequeue
      lazy val exception = new ActorKilledException("Actor has been stopped")
      while (invocation ne null) {
        invocation.channel.sendException(exception)
        invocation = m.dequeue
      }
    }
    while (m.systemDequeue() ne null) {
      //Empty the system messages
    }
  }

  override val toString = getClass.getSimpleName + "[" + name + "]"

  def suspend(actor: ActorCell): Unit =
    actor.mailbox.suspended.tryLock

  def resume(actor: ActorCell): Unit = {
    val mbox = actor.mailbox
    mbox.suspended.tryUnlock
    reRegisterForExecution(mbox)
  }
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

// TODO: should this be deleted, given that any dispatcher can now use UnboundedPriorityMailbox?

/**
 * A version of Dispatcher that gives all actors registered to it a priority mailbox,
 * prioritized according to the supplied comparator.
 *
 * The dispatcher will process the messages with the _lowest_ priority first.
 */
class PriorityDispatcher(
  name: String,
  val comparator: java.util.Comparator[Envelope],
  throughput: Int = Dispatchers.THROUGHPUT,
  throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  executorServiceFactoryProvider: ExecutorServiceFactoryProvider = ThreadPoolConfig()) extends Dispatcher(name, throughput, throughputDeadlineTime, mailboxType, executorServiceFactoryProvider) {

  def this(name: String, comparator: java.util.Comparator[Envelope], throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(name, comparator, throughput, throughputDeadlineTime, mailboxType, ThreadPoolConfig()) // Needed for Java API usage

  def this(name: String, comparator: java.util.Comparator[Envelope], throughput: Int, mailboxType: MailboxType) =
    this(name, comparator, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(name: String, comparator: java.util.Comparator[Envelope], throughput: Int) =
    this(name, comparator, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(name: String, comparator: java.util.Comparator[Envelope], executorServiceFactoryProvider: ExecutorServiceFactoryProvider) =
    this(name, comparator, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, executorServiceFactoryProvider)

  def this(name: String, comparator: java.util.Comparator[Envelope]) =
    this(name, comparator, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  override def createMailbox(actor: ActorCell): Mailbox = mailboxType match {
    case _: UnboundedMailbox          ⇒ UnboundedPriorityMailbox(comparator).create(this)
    case BoundedMailbox(cap, timeout) ⇒ BoundedPriorityMailbox(comparator, cap, timeout).create(this)
  }
}
