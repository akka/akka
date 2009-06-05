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

object EventBasedDispatcher extends MessageDispatcherBase {

  start
  
  //def dispatch(messageQueue: MessageQueue) = if (!active) {
  def start = if (!active) {
    active = true
    val messageDemultiplexer = new EventBasedDemultiplexer(messageQueue)
    selectorThread = new Thread {
      override def run = {
        while (active) {
          guard.synchronized { /* empty */ } // prevents risk for deadlock as described in [http://developers.sun.com/learning/javaoneonline/2006/coreplatform/TS-1315.pdf]
          try {
            messageDemultiplexer.select
          } catch { case e: InterruptedException => active = false }
          val queue = messageDemultiplexer.acquireSelectedQueue
          for (index <- 0 until queue.size) {
            val handle = queue.remove
            val handler = messageHandlers.get(handle.sender)
            if (handler != null) handler.handle(handle)
          }
        }
      }
    }
    selectorThread.start
  }
}

class EventBasedDemultiplexer(private val messageQueue: MessageQueue) extends MessageDemultiplexer {
  import java.util.{LinkedList, Queue}

  private val selectedQueue: Queue[MessageHandle] = new LinkedList[MessageHandle]

  def select = messageQueue.read(selectedQueue)

  def acquireSelectedQueue: Queue[MessageHandle] = selectedQueue

  def releaseSelectedQueue = throw new UnsupportedOperationException("EventBasedDemultiplexer can't release its queue")

  def wakeUp = throw new UnsupportedOperationException("EventBasedDemultiplexer can't be woken up")
}
