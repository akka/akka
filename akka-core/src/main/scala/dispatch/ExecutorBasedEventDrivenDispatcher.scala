/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

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
 */
class ExecutorBasedEventDrivenDispatcher(_name: String) extends MessageDispatcher with ThreadPoolBuilder {
  @volatile private var active: Boolean = false

  val name: String = "event-driven:executor:dispatcher:" + _name
  init

  def dispatch(invocation: MessageInvocation) = if (active) {
    executor.execute(new Runnable() {
      def run = {
        var lockAcquiredOnce = false
        // this do-wile loop is required to prevent missing new messages between the end of the inner while
        // loop and releasing the lock
        val lock = invocation.receiver.dispatcherLock
        val mailbox = invocation.receiver.mailbox
        do {
          if (lock.tryLock) {
            lockAcquiredOnce = true
            try {
              // Only dispatch if we got the lock. Otherwise another thread is already dispatching.
              var messageInvocation = mailbox.poll
              while (messageInvocation != null) {
                messageInvocation.invoke
                messageInvocation = mailbox.poll
              }
            } finally {
              lock.unlock
            }
          }
        } while ((lockAcquiredOnce && !mailbox.isEmpty))
      }
    })
  } else throw new IllegalStateException("Can't submit invocations to dispatcher since it's not started")

  def start = if (!active) {
    active = true
  }

  def shutdown = if (active) {
    log.debug("Shutting down ExecutorBasedEventDrivenDispatcher [%s]", name)
    executor.shutdownNow
    active = false
    references.clear
  }

  def usesActorMailbox = true

  def ensureNotActive: Unit = if (active) throw new IllegalStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")

  private[akka] def init = withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity.buildThreadPool
}
