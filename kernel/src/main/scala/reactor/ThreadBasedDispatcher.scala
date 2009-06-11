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

class ThreadBasedDispatcher extends MessageDispatcherBase {
  import java.util.concurrent.Executors
  import java.util.HashSet
  
  // FIXME: make configurable using configgy + JMX
  // FIXME: create one executor per invocation to dispatch(..), grab config settings for specific actor (set in registerHandler)
  private val threadPoolSize: Int = 100
  private val busyHandlers = new HashSet[AnyRef]
  private val handlerExecutor = Executors.newCachedThreadPool()

  def start = if (!active) {
    active = true
    val messageDemultiplexer = new ThreadBasedDemultiplexer(messageQueue)
    selectorThread = new Thread {
      //val enqued = new LinkedList[MessageHandle]
      override def run = {
        while (active) {
          try {
            guard.synchronized { /* empty */ } // prevents risk for deadlock as described in [http://developers.sun.com/learning/javaoneonline/2006/coreplatform/TS-1315.pdf]
            try {
              guard.synchronized { /* empty */ } // prevents risk for deadlock as described in [http://developers.sun.com/learning/javaoneonline/2006/coreplatform/TS-1315.pdf]
              messageDemultiplexer.select
            } catch {case e: InterruptedException => active = false}
            val queue = messageDemultiplexer.acquireSelectedQueue
//            while (!queue.isEmpty) {
            for (index <- 0 until queue.size) {
              val message = queue.peek
              val messageHandler = getIfNotBusy(message.sender)
              if (messageHandler.isDefined) {
                handlerExecutor.execute(new Runnable {
                  override def run = {
                    messageHandler.get.handle(message)
                    free(message.sender)
                    messageDemultiplexer.wakeUp
                  }
                })
                queue.remove
              }
            }
//            }
            if (!queue.isEmpty) {
              for (index <- 0 until queue.size) messageQueue.append(queue.remove)
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

  private def getIfNotBusy(key: AnyRef): Option[MessageHandler] = synchronized {
    if (!busyHandlers.contains(key) && messageHandlers.containsKey(key)) {
      busyHandlers.add(key)
      Some(messageHandlers.get(key))
    } else None
  }

  private def free(key: AnyRef) = synchronized { busyHandlers.remove(key) }
}

class ThreadBasedDemultiplexer(private val messageQueue: MessageQueue) extends MessageDemultiplexer {
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
