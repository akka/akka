/**
 * Copyright (C) 2009 Scalable Solutions.
 */

/**
 * Implements the Reactor pattern as defined in: [http://www.cs.wustl.edu/~schmidt/PDF/reactor-siemens.pdf].
 * See also this article: [http://today.java.net/cs/user/print/a/350].
 *
 * Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
 */
package se.scalablesolutions.akka.kernel.reactor


import kernel.actor.Invocation

class ProxyMessageDispatcher extends MessageDispatcherBase {
  import java.util.concurrent.Executors
  import java.util.HashSet
  import org.codehaus.aspectwerkz.joinpoint.JoinPoint
  
  // FIXME: make configurable using configgy + JMX
  // FIXME: create one executor per invocation to dispatch(..), grab config settings for specific actor (set in registerHandler)
  private val threadPoolSize: Int = 100
  private val handlerExecutor = Executors.newCachedThreadPool()

  def start = if (!active) {
    active = true
    val messageDemultiplexer = new ProxyDemultiplexer(messageQueue)
    selectorThread = new Thread {
      override def run = {
        while (active) {
          try {
            guard.synchronized { /* empty */ } // prevents risk for deadlock as described in [http://developers.sun.com/learning/javaoneonline/2006/coreplatform/TS-1315.pdf]
            try {
              messageDemultiplexer.select
            } catch {case e: InterruptedException => active = false}
            val queue = messageDemultiplexer.acquireSelectedQueue
            for (index <- 0 until queue.size) {
              val handle = queue.remove
              handlerExecutor.execute(new Runnable {
                val invocation = handle.message.asInstanceOf[Invocation]
                override def run = {
                  try {
                    val result = invocation.joinpoint.proceed
                    handle.future.completeWithResult(result)
                  } catch {
                    case e: Exception => handle.future.completeWithException(invocation.joinpoint.getTarget, e)
                  }
                  messageDemultiplexer.wakeUp
                }
              })
            }
          } finally {
            messageDemultiplexer.releaseSelectedQueue
          }
        }
      }
    };
    selectorThread.start
  }

  override protected def doShutdown = handlerExecutor.shutdownNow
}

class ProxyDemultiplexer(private val messageQueue: MessageQueue) extends MessageDemultiplexer {
  import java.util.concurrent.locks.ReentrantLock
  import java.util.{LinkedList, Queue}

  private val selectedQueue: Queue[MessageHandle] = new LinkedList[MessageHandle]
  private val selectedQueueLock = new ReentrantLock

  def select = try {
    selectedQueueLock.lock
    messageQueue.read(selectedQueue)
  } finally {
    selectedQueueLock.unlock
  }

  def acquireSelectedQueue: Queue[MessageHandle] = {
    selectedQueueLock.lock
    selectedQueue
  }

  def releaseSelectedQueue = {
    selectedQueue.clear
    selectedQueueLock.unlock
  }

  def wakeUp = messageQueue.interrupt
}
