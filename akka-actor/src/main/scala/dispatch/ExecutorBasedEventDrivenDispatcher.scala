/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{ActorRef, IllegalActorStateException}
import se.scalablesolutions.akka.util.ReflectiveAccess.EnterpriseModule

import java.util.Queue
import java.util.concurrent.{RejectedExecutionException, ConcurrentLinkedQueue, LinkedBlockingQueue}

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
 * the {@link se.scalablesolutions.akka.dispatch.Dispatchers} factory object.
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
  config: (ThreadPoolBuilder) => Unit = _ => ())
  extends MessageDispatcher with ThreadPoolBuilder {

  def this(_name: String, throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) = 
    this(_name, throughput, throughputDeadlineTime, mailboxType, _ => ())  // Needed for Java API usage

  def this(_name: String, throughput: Int, mailboxType: MailboxType) = 
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(_name: String, throughput: Int) = 
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String) = 
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  val mailboxType = Some(_mailboxType)

  @volatile private[akka] var active = false

  val name = "akka:event-driven:dispatcher:" + _name
  init

  def dispatch(invocation: MessageInvocation) = {
    val mbox = getMailbox(invocation.receiver)
    mbox enqueue invocation
    mbox.registerForExecution
  }

  /**
   * @return the mailbox associated with the actor
   */
  private def getMailbox(receiver: ActorRef) = {
    val mb = receiver.mailbox.asInstanceOf[MessageQueue with ExecutableMailbox]
    mb.register(this)
    mb
  }

  override def mailboxSize(actorRef: ActorRef) = getMailbox(actorRef).size

  def createTransientMailbox(actorRef: ActorRef, mailboxType: TransientMailboxType): AnyRef = mailboxType match {
    case UnboundedMailbox(blocking) => 
      new DefaultUnboundedMessageQueue(blocking) with ExecutableMailbox
    case BoundedMailbox(blocking, capacity, pushTimeOut) =>
      val cap = if (mailboxCapacity == -1) capacity else mailboxCapacity
      new DefaultBoundedMessageQueue(cap, pushTimeOut, blocking) with ExecutableMailbox
  }

  /**
   *  Creates and returns a durable mailbox for the given actor.
   */
  def createDurableMailbox(actorRef: ActorRef, mailboxType: DurableMailboxType): AnyRef = mailboxType match {
    // FIXME make generic (work for TypedActor as well)
    case FileBasedDurableMailbox(serializer)      => EnterpriseModule.createFileBasedMailbox(actorRef).asInstanceOf[MessageQueue]
    case ZooKeeperBasedDurableMailbox(serializer) => EnterpriseModule.createZooKeeperBasedMailbox(actorRef).asInstanceOf[MessageQueue]
    case BeanstalkBasedDurableMailbox(serializer) => EnterpriseModule.createBeanstalkBasedMailbox(actorRef).asInstanceOf[MessageQueue]
    case RedisBasedDurableMailbox(serializer)     => EnterpriseModule.createRedisBasedMailbox(actorRef).asInstanceOf[MessageQueue]
    case AMQPBasedDurableMailbox(serializer)      => throw new UnsupportedOperationException("AMQPBasedDurableMailbox is not yet supported")
    case JMSBasedDurableMailbox(serializer)       => throw new UnsupportedOperationException("JMSBasedDurableMailbox is not yet supported")
  }

  def start = if (!active) {
    log.debug("Starting up %s\n\twith throughput [%d]", toString, throughput)
    active = true
  }

  def shutdown = if (active) {
    log.debug("Shutting down %s", toString)
    executor.shutdownNow
    active = false
    uuids.clear
  }

  def ensureNotActive(): Unit = if (active) {
    throw new IllegalActorStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")
  }

  override def toString = "ExecutorBasedEventDrivenDispatcher[" + name + "]"

  // FIXME: should we have an unbounded queue and not bounded as default ????
  private[akka] def init = {
    withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity
    config(this)
    buildThreadPool
  }
}

/**
 * This is the behavior of an ExecutorBasedEventDrivenDispatchers mailbox.
 */
trait ExecutableMailbox extends Runnable { self: MessageQueue =>
  
  private var _dispatcher: Option[ExecutorBasedEventDrivenDispatcher] = None

  def register(md: ExecutorBasedEventDrivenDispatcher) = _dispatcher = Some(md)
  def dispatcher: ExecutorBasedEventDrivenDispatcher = _dispatcher.getOrElse(
    throw new IllegalActorStateException("mailbox.register(dispatcher) has not been invoked"))
  
  final def run = {
    val reschedule = try {
      processMailbox()
    } finally {
      dispatcherLock.unlock()
    }
    if (reschedule || !self.isEmpty) registerForExecution
  }

  /**
   * Process the messages in the mailbox
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  final def processMailbox(): Boolean = {
    var nextMessage = self.dequeue
    if (nextMessage ne null) {
      val throttle          = dispatcher.throughput > 0
      var processedMessages = 0
      val isDeadlineEnabled = throttle && dispatcher.throughputDeadlineTime > 0
      val started = if (isDeadlineEnabled) System.currentTimeMillis else 0
      do {
        nextMessage.invoke

        if (nextMessage.receiver.isBeingRestarted)
          return !self.isEmpty

        if (throttle) { // Will be elided when false
          processedMessages += 1
          if ((processedMessages >= dispatcher.throughput) || 
              (isDeadlineEnabled && (System.currentTimeMillis - started) >= dispatcher.throughputDeadlineTime)) // If we're throttled, break out
            return !self.isEmpty
        }
        nextMessage = self.dequeue
      } while (nextMessage ne null)
    }
    false
  }


  def registerForExecution: Unit = if (dispatcher.active) {
    if (dispatcherLock.tryLock()) {
      try {
        dispatcher.execute(this)
      } catch {
        case e: RejectedExecutionException =>
          dispatcherLock.unlock()
          throw e
      }
    }
  } else dispatcher.log.warning("%s is shut down,\n\tignoring the rest of the messages in the mailbox of\n\t%s", toString, this)
}

