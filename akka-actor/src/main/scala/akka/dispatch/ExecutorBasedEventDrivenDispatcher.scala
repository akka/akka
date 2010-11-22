/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.actor.{ActorRef, IllegalActorStateException}
import akka.util.ReflectiveAccess.AkkaCloudModule

import java.util.Queue
import akka.util.Switch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent. {ExecutorService, RejectedExecutionException, ConcurrentLinkedQueue, LinkedBlockingQueue}

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
 *   val dispatcher = new ExecutorBasedEventDrivenDispatcher("name")
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
 *   ExecutorBasedEventDrivenDispatcher dispatcher = new ExecutorBasedEventDrivenDispatcher("name");
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
 *                   Larger values (or zero or negative) increase througput, smaller values increase fairness
 */
class ExecutorBasedEventDrivenDispatcher(
  _name: String,
  val throughput: Int = Dispatchers.THROUGHPUT,
  val throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  _mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  val config: ThreadPoolConfig = ThreadPoolConfig())
  extends MessageDispatcher {

  def this(_name: String, throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(_name, throughput, throughputDeadlineTime, mailboxType,ThreadPoolConfig())  // Needed for Java API usage

  def this(_name: String, throughput: Int, mailboxType: MailboxType) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(_name: String, throughput: Int) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String, _config: ThreadPoolConfig) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, _config)

  def this(_name: String) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  val name        = "akka:event-driven:dispatcher:" + _name
  val mailboxType = Some(_mailboxType)

  private[akka] val threadFactory = new MonitorableThreadFactory(name)
  private[akka] val executorService = new AtomicReference[ExecutorService](config.createLazyExecutorService(threadFactory))

  private[akka] def dispatch(invocation: MessageInvocation) = {
    val mbox = getMailbox(invocation.receiver)
    mbox enqueue invocation
    registerForExecution(mbox)
  }

  /**
   * @return the mailbox associated with the actor
   */
  private def getMailbox(receiver: ActorRef) = receiver.mailbox.asInstanceOf[MessageQueue with ExecutableMailbox]

  override def mailboxSize(actorRef: ActorRef) = getMailbox(actorRef).size

  def createTransientMailbox(actorRef: ActorRef, mailboxType: TransientMailboxType): AnyRef = mailboxType match {
    case UnboundedMailbox(blocking) => new DefaultUnboundedMessageQueue(blocking) with ExecutableMailbox {
      def dispatcher = ExecutorBasedEventDrivenDispatcher.this
    }

    case BoundedMailbox(blocking, capacity, pushTimeOut) =>
      new DefaultBoundedMessageQueue(capacity, pushTimeOut, blocking) with ExecutableMailbox {
        def dispatcher = ExecutorBasedEventDrivenDispatcher.this
      }
  }

  /**
   *  Creates and returns a durable mailbox for the given actor.
   */
  def createDurableMailbox(actorRef: ActorRef, mailboxType: DurableMailboxType): AnyRef = mailboxType match {
    // FIXME make generic (work for TypedActor as well)
    case FileBasedDurableMailbox(serializer)      => AkkaCloudModule.createFileBasedMailbox(actorRef).asInstanceOf[MessageQueue]
    case ZooKeeperBasedDurableMailbox(serializer) => AkkaCloudModule.createZooKeeperBasedMailbox(actorRef).asInstanceOf[MessageQueue]
    case BeanstalkBasedDurableMailbox(serializer) => AkkaCloudModule.createBeanstalkBasedMailbox(actorRef).asInstanceOf[MessageQueue]
    case RedisBasedDurableMailbox(serializer)     => AkkaCloudModule.createRedisBasedMailbox(actorRef).asInstanceOf[MessageQueue]
    case AMQPBasedDurableMailbox(serializer)      => throw new UnsupportedOperationException("AMQPBasedDurableMailbox is not yet supported")
    case JMSBasedDurableMailbox(serializer)       => throw new UnsupportedOperationException("JMSBasedDurableMailbox is not yet supported")
  }

  private[akka] def start = log.debug("Starting up %s\n\twith throughput [%d]", toString, throughput)

  private[akka] def shutdown {
    val old = executorService.getAndSet(config.createLazyExecutorService(threadFactory))
    if (old ne null) {
      log.debug("Shutting down %s", toString)
      old.shutdownNow()
    }
  }


  private[akka] def registerForExecution(mbox: MessageQueue with ExecutableMailbox): Unit = if (active.isOn) {
    if (mbox.suspended.isOff && mbox.dispatcherLock.tryLock()) {
      try {
        executorService.get() execute mbox
      } catch {
        case e: RejectedExecutionException =>
          mbox.dispatcherLock.unlock()
          throw e
      }
    }
  } else log.warning("%s is shut down,\n\tignoring the rest of the messages in the mailbox of\n\t%s", this, mbox)

  override val toString = getClass.getSimpleName + "[" + name + "]"

  def suspend(actorRef: ActorRef) {
    log.debug("Suspending %s",actorRef.uuid)
    getMailbox(actorRef).suspended.switchOn
  }

  def resume(actorRef: ActorRef) {
    log.debug("Resuming %s",actorRef.uuid)
    val mbox = getMailbox(actorRef)
    mbox.suspended.switchOff
    registerForExecution(mbox)
  }
}

/**
 * This is the behavior of an ExecutorBasedEventDrivenDispatchers mailbox.
 */
trait ExecutableMailbox extends Runnable { self: MessageQueue =>

  def dispatcher: ExecutorBasedEventDrivenDispatcher

  final def run = {
    val reschedule = try {
      try { processMailbox() } catch { case ie: InterruptedException => true }
    } finally {
      dispatcherLock.unlock()
    }
    if (reschedule || !self.isEmpty)
      dispatcher.registerForExecution(this)
  }

  /**
   * Process the messages in the mailbox
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  final def processMailbox(): Boolean = {
    if (self.suspended.isOn)
      true
    else {
      var nextMessage = self.dequeue
      if (nextMessage ne null) {
        val throttle          = dispatcher.throughput > 0
        var processedMessages = 0
        val isDeadlineEnabled = throttle && dispatcher.throughputDeadlineTime > 0
        val started = if (isDeadlineEnabled) System.currentTimeMillis else 0
        do {
          nextMessage.invoke

          if (throttle) { // Will be elided when false
            processedMessages += 1
            if ((processedMessages >= dispatcher.throughput) ||
                    (isDeadlineEnabled && (System.currentTimeMillis - started) >= dispatcher.throughputDeadlineTime)) // If we're throttled, break out
              return !self.isEmpty
          }

          if (self.suspended.isOn)
            return true

          nextMessage = self.dequeue
        } while (nextMessage ne null)
      }
      false
    }
  }
}

