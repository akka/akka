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

import java.util.concurrent.{ConcurrentHashMap, Executors}
import java.util.concurrent.locks.ReentrantLock
import java.util.{HashSet, LinkedList, Queue}

class ThreadBasedDispatcher(val threadPoolSize: Int) extends MessageDispatcher {
  private val handlers = new ConcurrentHashMap[AnyRef, MessageHandler]
  private val busyHandlers = new HashSet[AnyRef]
  private val handlerExecutor = Executors.newFixedThreadPool(threadPoolSize)
  @volatile private var selectorThread: Thread = null
  @volatile private var active: Boolean = false

  def registerHandler(key: AnyRef, handler: MessageHandler) = handlers.put(key, handler)

  def unregisterHandler(key: AnyRef) = handlers.remove(key)

  def dispatch(messageQueue: MessageQueue) = {
    if (!active) {
      active = true
      val messageDemultiplexer = new ThreadBasedDemultiplexer(messageQueue)
      selectorThread = new Thread {
        override def run = {
          while (active) {
            try {
              messageDemultiplexer.select
              val handles = messageDemultiplexer.acquireSelectedQueue
              for (index <- 0 to handles.size) {
                val handle = handles.peek
                val handler = checkIfNotBusyThenGet(handle.key)
                if (handler.isDefined) {
                  handlerExecutor.execute(new Runnable {
                    override def run = {
                      handler.get.handle(handle)
                      free(handle.key)
                      messageDemultiplexer.wakeUp
                    }
                  })
                  handles.remove
                }
              }
            } finally {
              messageDemultiplexer.releaseSelectedQueue
            }
          }
        }
      };
      selectorThread.start();
    }
  }

  def shutdown = if (active) {
    active = false
    selectorThread.interrupt
    handlerExecutor.shutdownNow
  }

  private def checkIfNotBusyThenGet(key: AnyRef): Option[MessageHandler] = synchronized {
    if (!busyHandlers.contains(key) && handlers.containsKey(key)) {
      busyHandlers.add(key)
      Some(handlers.get(key))
    } else None
  }

  private def free(key: AnyRef) = synchronized { busyHandlers.remove(key) }
}

class ThreadBasedDemultiplexer(private val messageQueue: MessageQueue) extends MessageDemultiplexer {
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

  def releaseSelectedQueue = selectedQueueLock.unlock

  def wakeUp = messageQueue.interrupt
}
