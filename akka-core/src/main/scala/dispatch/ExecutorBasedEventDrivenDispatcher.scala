/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{ActorRef, IllegalActorStateException}

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
class ExecutorBasedEventDrivenDispatcher(_name: String, throughput: Int = Dispatchers.THROUGHPUT) extends MessageDispatcher with ThreadPoolBuilder {
  def this(_name: String) = this(_name, Dispatchers.THROUGHPUT) // Needed for Java API usage

  @volatile private var active: Boolean = false

  val name: String = "event-driven:executor:dispatcher:" + _name
  init

  def dispatch(invocation: MessageInvocation) = dispatch(invocation.receiver)

  def dispatch(receiver: ActorRef): Unit = if (active) {
    executor.execute(new Runnable() {
      def run = {
        var lockAcquiredOnce = false
        var finishedBeforeMailboxEmpty = false
        val lock = receiver.dispatcherLock
        val mailbox = receiver.mailbox
        // this do-while loop is required to prevent missing new messages between the end of the inner while
        // loop and releasing the lock
        do {
          if (lock.tryLock) {
            // Only dispatch if we got the lock. Otherwise another thread is already dispatching.
            lockAcquiredOnce = true
            try {
              finishedBeforeMailboxEmpty = processMailbox(receiver)
            } finally {
              lock.unlock
              if (finishedBeforeMailboxEmpty) dispatch(receiver)
            }
          }
        } while ((lockAcquiredOnce && !finishedBeforeMailboxEmpty && !mailbox.isEmpty))
      }
    })
  } else throw new IllegalActorStateException("Can't submit invocations to dispatcher since it's not started")


  /**
   * Process the messages in the mailbox of the given actor.
   *
   * @return true if the processing finished before the mailbox was empty, due to the throughput constraint
   */
  def processMailbox(receiver: ActorRef): Boolean = {
    var processedMessages = 0
    var messageInvocation = receiver.mailbox.poll
    while (messageInvocation != null) {
      messageInvocation.invoke
      processedMessages += 1
      // check if we simply continue with other messages, or reached the throughput limit
      if (throughput <= 0 || processedMessages < throughput)
        messageInvocation = receiver.mailbox.poll
      else {
        return !receiver.mailbox.isEmpty
        messageInvocation = null
      }
    }

    return false
  }

  def start = if (!active) {
    log.debug("Starting ExecutorBasedEventDrivenDispatcher [%s]", name)
    log.debug("Throughput for %s = %d", name, throughput)
    active = true
  }

  def shutdown = if (active) {
    log.debug("Shutting down ExecutorBasedEventDrivenDispatcher [%s]", name)
    executor.shutdownNow
    active = false
    references.clear
  }

  def usesActorMailbox = true

  def ensureNotActive(): Unit = if (active) throw new IllegalActorStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")

  private[akka] def init = withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity.buildThreadPool
}
