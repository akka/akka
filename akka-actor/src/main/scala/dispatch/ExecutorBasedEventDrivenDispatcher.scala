/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{ActorRef, IllegalActorStateException}

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
  val throughputDeadlineMs: Int = Dispatchers.THROUGHPUT_DEADLINE_MS,
  mailboxConfig: MailboxConfig = Dispatchers.MAILBOX_CONFIG,
  config: (ThreadPoolBuilder) => Unit = _ => ()) extends MessageDispatcher with ThreadPoolBuilder {

  def this(_name: String, throughput: Int, throughputDeadlineMs: Int, capacity: Int) = this(_name,throughput,throughputDeadlineMs,MailboxConfig(capacity,None,false))
  def this(_name: String, throughput: Int) = this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_MS, Dispatchers.MAILBOX_CAPACITY) // Needed for Java API usage
  def this(_name: String) = this(_name,Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_MS,Dispatchers.MAILBOX_CAPACITY) // Needed for Java API usage

  //FIXME remove this from ThreadPoolBuilder
  mailboxCapacity = mailboxConfig.capacity

  @volatile private var active: Boolean = false

  val name = "akka:event-driven:dispatcher:" + _name
  init

  /**
   * This is the behavior of an ExecutorBasedEventDrivenDispatchers mailbox
   */
  trait ExecutableMailbox extends Runnable { self: MessageQueue =>
    final def run = {

      val reschedule = try {
        processMailbox()
      } finally {
        dispatcherLock.unlock()
      }

      if (reschedule || !self.isEmpty)
        registerForExecution(self)
    }

  /**
   * Process the messages in the mailbox
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
   final def processMailbox(): Boolean = {
     var nextMessage       = self.dequeue
     if (nextMessage ne null) {
       val throttle          = throughput > 0
       var processedMessages = 0
       val isDeadlineEnabled = throttle && throughputDeadlineMs > 0
       val started = if (isDeadlineEnabled) System.currentTimeMillis else 0

       do {
         nextMessage.invoke

         if(throttle) { //Will be elided when false
           processedMessages += 1
           if ((processedMessages >= throughput)
           || (isDeadlineEnabled && (System.currentTimeMillis - started) >= throughputDeadlineMs)) //If we're throttled, break out
             return !self.isEmpty
         }
         nextMessage = self.dequeue
       }
       while (nextMessage ne null)
     }

     false
    }
  }

  def dispatch(invocation: MessageInvocation) = {
    val mbox = getMailbox(invocation.receiver)
    mbox enqueue invocation
    registerForExecution(mbox)
  }

  protected def registerForExecution(mailbox: MessageQueue with ExecutableMailbox): Unit = if (active) {
    if (mailbox.dispatcherLock.tryLock()) {
      try {
        executor execute mailbox
      } catch {
        case e: RejectedExecutionException =>
          mailbox.dispatcherLock.unlock()
          throw e
      }
    }
  } else {
    log.warning("%s is shut down,\n\tignoring the rest of the messages in the mailbox of\n\t%s", toString, mailbox)
  }

  /**
   * @return the mailbox associated with the actor
   */
  private def getMailbox(receiver: ActorRef) = receiver.mailbox.asInstanceOf[MessageQueue with ExecutableMailbox]

  override def mailboxSize(actorRef: ActorRef) = getMailbox(actorRef).size

  override def createMailbox(actorRef: ActorRef): AnyRef = {
    if (mailboxCapacity > 0)
      new DefaultBoundedMessageQueue(mailboxCapacity,mailboxConfig.pushTimeOut,blockDequeue = false) with ExecutableMailbox
    else
      new DefaultUnboundedMessageQueue(blockDequeue = false) with ExecutableMailbox
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