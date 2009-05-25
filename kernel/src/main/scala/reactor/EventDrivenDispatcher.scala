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

class EventDrivenDispatcher extends MessageDispatcher {
  private val handlers = new ConcurrentHashMap[AnyRef, MessageHandler]
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false
  private val guard = new Object

  def registerHandler(key: AnyRef, handler: MessageHandler) = handlers.put(key, handler)

  def unregisterHandler(key: AnyRef) = handlers.remove(key)

  def dispatch(messageQueue: MessageQueue) = if (!active) {
    active = true
    val messageDemultiplexer = new EventDrivenDemultiplexer(messageQueue)
    selectorThread = new Thread {
      override def run = {
        while (active) {
          guard.synchronized { /* empty */ }
          messageDemultiplexer.select
          val handles = messageDemultiplexer.acquireSelectedQueue
          val handlesList = handles.toArray.toList.asInstanceOf[List[MessageHandle]]
          for (index <- 0 to handles.size) {
            val handle = handles.remove
            val handler = handlers.get(handle.key)
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

class EventDrivenDemultiplexer(private val messageQueue: MessageQueue) extends MessageDemultiplexer {
  private val selectedQueue: Queue[MessageHandle] = new LinkedList[MessageHandle]

  def select = messageQueue.read(selectedQueue)

  def acquireSelectedQueue: Queue[MessageHandle] = selectedQueue

  def releaseSelectedQueue = {
    throw new UnsupportedOperationException
  }

  def wakeUp = {
    throw new UnsupportedOperationException
  }
}
