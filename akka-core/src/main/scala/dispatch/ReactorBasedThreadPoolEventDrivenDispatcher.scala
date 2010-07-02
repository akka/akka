/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.concurrent.locks.ReentrantLock

import java.util.{HashSet, HashMap, LinkedList, List}

import se.scalablesolutions.akka.actor.IllegalActorStateException

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

  private var fair = true
  private val busyActors = new HashSet[AnyRef]
  private val messageDemultiplexer = new Demultiplexer(queue)

  // build default thread pool
  withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity.buildThreadPool

  def start = if (!active) {
    active = true

    /**
     * This dispatcher code is based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
     */
    selectorThread = new Thread(name) {
      override def run = {
        while (active) {
          try {
            try {
        //      guard.synchronized { /* empty */ } // prevents risk for deadlock as described in [http://developers.sun.com/learning/javaoneonline/2006/coreplatform/TS-1315.pdf]
              messageDemultiplexer.select
            } catch { case e: InterruptedException => active = false }
            process(messageDemultiplexer.acquireSelectedInvocations)
          } finally {
            messageDemultiplexer.releaseSelectedInvocations
          }
        }
      }
    };
    selectorThread.start
  }

  override protected def doShutdown = executor.shutdownNow

  private def process(selectedInvocations: List[MessageInvocation]) = synchronized {
    var nrOfBusyMessages = 0
    val totalNrOfActors = messageInvokers.size
    val totalNrOfBusyActors = busyActors.size
    val invocations = selectedInvocations.iterator
    while (invocations.hasNext && totalNrOfActors > totalNrOfBusyActors && passFairnessCheck(nrOfBusyMessages)) {
      val invocation = invocations.next
      if (invocation eq null) throw new IllegalActorStateException("Message invocation is null [" + invocation + "]")
      if (!busyActors.contains(invocation.receiver)) {
        val invoker = messageInvokers.get(invocation.receiver)
        if (invoker eq null) throw new IllegalActorStateException("Message invoker for invocation [" + invocation + "] is null")
        resume(invocation.receiver)
        invocations.remove
        executor.execute(new Runnable() {
          def run = {
            invoker.invoke(invocation)
            suspend(invocation.receiver)
            messageDemultiplexer.wakeUp
          }
        })
      } else nrOfBusyMessages += 1
    }
  }

  private def resume(actor: AnyRef) = synchronized {
    busyActors.add(actor)
  }

  private def suspend(actor: AnyRef) = synchronized {
    busyActors.remove(actor)
  }

  private def passFairnessCheck(nrOfBusyMessages: Int) = {
    if (fair) true
    else nrOfBusyMessages < 100
  }

  def usesActorMailbox = false

  def ensureNotActive: Unit = if (active) throw new IllegalActorStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")

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
