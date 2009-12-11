/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.dispatch

import java.util.concurrent.locks.ReentrantLock

import java.util.{HashSet, HashMap, LinkedList, List}

/**
 * Implements the Reactor pattern as defined in: [http://www.cs.wustl.edu/~schmidt/PDF/reactor-siemens.pdf].<br/>
 * See also this article: [http://today.java.net/cs/user/print/a/350].
 * <p/>
 * 
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
 *   val dispatcher = new ReactorBasedThreadPoolEventDrivenDispatcher("name")
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
 *   ReactorBasedThreadPoolEventDrivenDispatcher dispatcher = new ReactorBasedThreadPoolEventDrivenDispatcher("name");
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
class ReactorBasedThreadPoolEventDrivenDispatcher(_name: String)
    extends AbstractReactorBasedEventDrivenDispatcher("event-driven:reactor:thread-pool:dispatcher:" + _name)
    with ThreadPoolBuilder {

  private val busyInvokers = new HashSet[AnyRef]

  // build default thread pool
  withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity.buildThreadPool
  
  def start = if (!active) {
    active = true

    /**
     * This dispatcher code is based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
     */
    val messageDemultiplexer = new Demultiplexer(queue)
    selectorThread = new Thread(name) {
      override def run = {
        while (active) {
          try {
            try {
              guard.synchronized { /* empty */ } // prevents risk for deadlock as described in [http://developers.sun.com/learning/javaoneonline/2006/coreplatform/TS-1315.pdf]
              messageDemultiplexer.select
            } catch { case e: InterruptedException => active = false }
            val selectedInvocations = messageDemultiplexer.acquireSelectedInvocations
            val reservedInvocations = reserve(selectedInvocations)
            val it = reservedInvocations.entrySet.iterator
            while (it.hasNext) {
              val entry = it.next
              val invocation = entry.getKey
              val invoker = entry.getValue
              executor.execute(new Runnable() {
                def run = {
                  invoker.invoke(invocation)
                  free(invocation.receiver)
                  messageDemultiplexer.wakeUp
                }
              })
            }
          } finally {
            messageDemultiplexer.releaseSelectedInvocations
          }
        }
      }
    };
    selectorThread.start
  }

  override protected def doShutdown = executor.shutdownNow

  private def reserve(invocations: List[MessageInvocation]): HashMap[MessageInvocation, MessageInvoker] = guard.synchronized {
    val result = new HashMap[MessageInvocation, MessageInvoker]
    val iterator = invocations.iterator
    while (iterator.hasNext) {
      val invocation = iterator.next
      if (invocation == null) throw new IllegalStateException("Message invocation is null [" + invocation + "]")
      if (!busyInvokers.contains(invocation.receiver)) {
        val invoker = messageHandlers.get(invocation.receiver)
        if (invoker == null) throw new IllegalStateException("Message invoker for invocation [" + invocation + "] is null")
        result.put(invocation, invoker)
        busyInvokers.add(invocation.receiver)
        iterator.remove
      }
    }
    result
  }

  def ensureNotActive: Unit = if (active) throw new IllegalStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")

  private def free(invoker: AnyRef) = guard.synchronized {
    busyInvokers.remove(invoker)
  }
  
  class Demultiplexer(private val messageQueue: ReactiveMessageQueue) extends MessageDemultiplexer {
    private val selectedInvocations: List[MessageInvocation] = new LinkedList[MessageInvocation]
    private val selectedInvocationsLock = new ReentrantLock

    def select = try {
      selectedInvocationsLock.lock
      messageQueue.read(selectedInvocations)
    } finally {
      selectedInvocationsLock.unlock
    }

    def acquireSelectedInvocations: List[MessageInvocation] = {
      selectedInvocationsLock.lock
      selectedInvocations
    }

    def releaseSelectedInvocations = selectedInvocationsLock.unlock

    def wakeUp = messageQueue.interrupt
  }
}
