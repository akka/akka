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

import java.util.concurrent.{ConcurrentMap, ConcurrentHashMap}
import java.util.{LinkedList, Queue}                    

class EventBasedDispatcher extends MessageDispatcher {
  private val handlers = new ConcurrentHashMap[AnyRef, MessageHandler]
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false

  def registerHandler(key: AnyRef, handler: MessageHandler) = handlers.put(key, handler)

  def unregisterHandler(key: AnyRef) = handlers.remove(key)

  def dispatch(messageQueue: MessageQueue) = if (!active) {
    active = true
    val messageDemultiplexer = new EventBasedDemultiplexer(messageQueue)
    selectorThread = new Thread {
      override def run = {
        while (active) {
          messageDemultiplexer.select
          val queue = messageDemultiplexer.acquireSelectedQueue
          for (index <- 0 to queue.size) {
            val handle = queue.remove
            val handler = handlers.get(handle.sender)
            if (handler != null) handler.handle(handle)
          }
        }
      }
    }
    selectorThread.start
  }

  def shutdown = if (active) {
    active = false
    selectorThread.interrupt
  }
}

class EventBasedDemultiplexer(private val messageQueue: MessageQueue) extends MessageDemultiplexer {
  private val selectedQueue: Queue[MessageHandle] = new LinkedList[MessageHandle]

  def select = messageQueue.read(selectedQueue)

  def acquireSelectedQueue: Queue[MessageHandle] = selectedQueue

  def releaseSelectedQueue = throw new UnsupportedOperationException("EventBasedDemultiplexer can't release its queue since it is always alive and kicking")

  def wakeUp = throw new UnsupportedOperationException("EventBasedDemultiplexer can't be woken up isince always alive and kicking")
}
