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

  protected[akka] def dispatch(invocation: MessageInvocation) = {
    val mbox = getMailbox(invocation.receiver)
    if (mbox ne null) {
      mbox enqueue invocation
      registerForExecution(mbox)
    }
  }

  protected[akka] def systemDispatch(invocation: SystemMessageInvocation) = {
    val mbox = getMailbox(invocation.receiver)
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

  /**
   * @return the mailbox associated with the actor
   */
  protected def getMailbox(receiver: ActorCell) = receiver.mailbox.asInstanceOf[MessageQueue with ExecutableMailbox]

  override def mailboxIsEmpty(actor: ActorCell): Boolean = getMailbox(actor).isEmpty

  override def mailboxSize(actor: ActorCell): Int = getMailbox(actor).size

  def createMailbox(actor: ActorCell): AnyRef = mailboxType match {
    case b: UnboundedMailbox ⇒
      new ConcurrentLinkedQueue[MessageInvocation] with MessageQueue with ExecutableMailbox {
        @inline
        final def dispatcher = Dispatcher.this
        @inline
        final def enqueue(m: MessageInvocation) = this.add(m)
        @inline
        final def dequeue(): MessageInvocation = this.poll()
      }
    case b: BoundedMailbox ⇒
      new DefaultBoundedMessageQueue(b.capacity, b.pushTimeOut) with ExecutableMailbox {
        @inline
        final def dispatcher = Dispatcher.this
      }
  }

  protected[akka] def start {}

  protected[akka] def shutdown {
    val old = executorService.getAndSet(new LazyExecutorServiceWrapper(executorServiceFactory.createExecutorService))
    if (old ne null) {
      old.shutdownNow()
    }
  }

  protected[akka] def registerForExecution(mbox: MessageQueue with ExecutableMailbox): Unit = {
    if (mbox.dispatcherLock.tryLock()) {
      if (active.isOn && (!mbox.suspended.locked || !mbox.systemMessages.isEmpty)) { //If the dispatcher is active and the actor not suspended
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

  protected[akka] def reRegisterForExecution(mbox: MessageQueue with ExecutableMailbox): Unit =
    registerForExecution(mbox)

  protected override def cleanUpMailboxFor(actor: ActorCell) {
    val m = getMailbox(actor)
    if (!m.isEmpty) {
      var invocation = m.dequeue
      lazy val exception = new ActorKilledException("Actor has been stopped")
      while (invocation ne null) {
        invocation.channel.sendException(exception)
        invocation = m.dequeue
      }
    }
  }

  override val toString = getClass.getSimpleName + "[" + name + "]"

  def suspend(actor: ActorCell): Unit =
    getMailbox(actor).suspended.tryLock

  def resume(actor: ActorCell): Unit = {
    val mbox = getMailbox(actor)
    mbox.suspended.tryUnlock
    reRegisterForExecution(mbox)
  }
}

/**
 * This is the behavior of an Dispatchers mailbox.
 */
trait ExecutableMailbox extends Runnable { self: MessageQueue ⇒

  def dispatcher: Dispatcher

  final def run = {
    try { processMailbox() } catch {
      case ie: InterruptedException ⇒ Thread.currentThread().interrupt() //Restore interrupt
    } finally {
      dispatcherLock.unlock()
      if (!self.isEmpty || !self.systemMessages.isEmpty)
        dispatcher.reRegisterForExecution(this)
    }
  }

  /**
   * Process the messages in the mailbox
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  final def processMailbox() {
    processAllSystemMessages()
    if (!self.suspended.locked) {
      var nextMessage = self.dequeue
      if (nextMessage ne null) { //If we have a message
        if (dispatcher.throughput <= 1) //If we only run one message per process
          nextMessage.invoke //Just run it
        else { //But otherwise, if we are throttled, we need to do some book-keeping
          var processedMessages = 0
          val isDeadlineEnabled = dispatcher.throughputDeadlineTime > 0
          val deadlineNs = if (isDeadlineEnabled) System.nanoTime + TimeUnit.MILLISECONDS.toNanos(dispatcher.throughputDeadlineTime)
          else 0
          do {
            nextMessage.invoke
            processAllSystemMessages()
            nextMessage =
              if (self.suspended.locked) {
                null // If we are suspended, abort
              } else { // If we aren't suspended, we need to make sure we're not overstepping our boundaries
                processedMessages += 1
                if ((processedMessages >= dispatcher.throughput) || (isDeadlineEnabled && System.nanoTime >= deadlineNs)) // If we're throttled, break out
                  null //We reached our boundaries, abort
                else self.dequeue //Dequeue the next message
              }
          } while (nextMessage ne null)
        }
      }
    }
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
abstract class PriorityGenerator extends java.util.Comparator[MessageInvocation] {
  def gen(message: Any): Int

  final def compare(thisMessage: MessageInvocation, thatMessage: MessageInvocation): Int =
    gen(thisMessage.message) - gen(thatMessage.message)
}

/**
 * A version of Dispatcher that gives all actors registered to it a priority mailbox,
 * prioritized according to the supplied comparator.
 *
 * The dispatcher will process the messages with the _lowest_ priority first.
 */
class PriorityDispatcher(
  name: String,
  val comparator: java.util.Comparator[MessageInvocation],
  throughput: Int = Dispatchers.THROUGHPUT,
  throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  executorServiceFactoryProvider: ExecutorServiceFactoryProvider = ThreadPoolConfig()) extends Dispatcher(name, throughput, throughputDeadlineTime, mailboxType, executorServiceFactoryProvider) with PriorityMailbox {

  def this(name: String, comparator: java.util.Comparator[MessageInvocation], throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(name, comparator, throughput, throughputDeadlineTime, mailboxType, ThreadPoolConfig()) // Needed for Java API usage

  def this(name: String, comparator: java.util.Comparator[MessageInvocation], throughput: Int, mailboxType: MailboxType) =
    this(name, comparator, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(name: String, comparator: java.util.Comparator[MessageInvocation], throughput: Int) =
    this(name, comparator, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(name: String, comparator: java.util.Comparator[MessageInvocation], executorServiceFactoryProvider: ExecutorServiceFactoryProvider) =
    this(name, comparator, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, executorServiceFactoryProvider)

  def this(name: String, comparator: java.util.Comparator[MessageInvocation]) =
    this(name, comparator, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage
}

/**
 * Can be used to give an Dispatcher's actors priority-enabled mailboxes
 *
 * Usage:
 * new Dispatcher(...) with PriorityMailbox {
 *   val comparator = ...comparator that determines mailbox priority ordering...
 * }
 */
trait PriorityMailbox { self: Dispatcher ⇒
  def comparator: java.util.Comparator[MessageInvocation]

  override def createMailbox(actor: ActorCell): AnyRef = self.mailboxType match {
    case b: UnboundedMailbox ⇒
      new UnboundedPriorityMessageQueue(comparator) with ExecutableMailbox {
        @inline
        final def dispatcher = self
      }

    case b: BoundedMailbox ⇒
      new BoundedPriorityMessageQueue(b.capacity, b.pushTimeOut, comparator) with ExecutableMailbox {
        @inline
        final def dispatcher = self
      }
  }
}
