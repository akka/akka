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

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ExecutorService
import java.util.{HashSet, LinkedList, Queue}

class EventBasedThreadPoolDispatcher(private val threadPool: ExecutorService) extends MessageDispatcherBase {
  private val busyHandlers = new HashSet[AnyRef]

  def start = if (!active) {
    active = true
    val messageDemultiplexer = new EventBasedThreadPoolDemultiplexer(messageQueue)
    selectorThread = new Thread {
      override def run = {
        while (active) {
          try {
            try {
              guard.synchronized { /* empty */ } // prevents risk for deadlock as described in [http://developers.sun.com/learning/javaoneonline/2006/coreplatform/TS-1315.pdf]
              messageDemultiplexer.select
            } catch {case e: InterruptedException => active = false}
            val queue = messageDemultiplexer.acquireSelectedQueue
            for (index <- 0 until queue.size) {
              val message = queue.peek
              val messageHandler = getIfNotBusy(message.sender)
              if (messageHandler.isDefined) {
                threadPool.execute(new Runnable {
                  override def run = {
                    messageHandler.get.handle(message)
                    free(message.sender)
                    messageDemultiplexer.wakeUp
                  }
                })
                queue.remove
              }
            }
          } finally {
            messageDemultiplexer.releaseSelectedQueue
          }
        }
      }
    };
    selectorThread.start
  }

  override protected def doShutdown = threadPool.shutdownNow

  private def getIfNotBusy(key: AnyRef): Option[MessageHandler] = guard.synchronized {
    if (!busyHandlers.contains(key) && messageHandlers.containsKey(key)) {
      busyHandlers.add(key)
      Some(messageHandlers.get(key))
    } else None
  }

  private def free(key: AnyRef) = guard.synchronized {
    busyHandlers.remove(key)
  }
}

class EventBasedThreadPoolDemultiplexer(private val messageQueue: MessageQueue) extends MessageDemultiplexer {
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
    //selectedQueue.clear
    selectedQueueLock.unlock
  }

  def wakeUp = messageQueue.interrupt
}
