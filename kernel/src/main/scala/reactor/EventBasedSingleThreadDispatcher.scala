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

import java.util.{LinkedList, Queue, List}

class EventBasedSingleThreadDispatcher extends MessageDispatcherBase {
  def start = if (!active) {
    active = true
    val messageDemultiplexer = new EventBasedSingleThreadDemultiplexer(queue)
    selectorThread = new Thread {
      override def run = {
        while (active) {
          try {
            messageDemultiplexer.select
          } catch { case e: InterruptedException => active = false }
          val selectedInvocations = messageDemultiplexer.acquireSelectedInvocations.iterator
          while (selectedInvocations.hasNext) {
            val invocation = selectedInvocations.next
            val invoker = messageHandlers.get(invocation.sender)
            if (invoker != null) invoker.invoke(invocation)
            selectedInvocations.remove
          }
        }
      }
    }
    selectorThread.start
  }
}

class EventBasedSingleThreadDemultiplexer(private val messageQueue: ReactiveMessageQueue) extends MessageDemultiplexer {

  private val selectedQueue: List[MessageInvocation] = new LinkedList[MessageInvocation]

  def select = messageQueue.read(selectedQueue)

  def acquireSelectedInvocations: List[MessageInvocation] = selectedQueue

  def releaseSelectedInvocations = throw new UnsupportedOperationException("EventBasedSingleThreadDemultiplexer can't release its queue")

  def wakeUp = throw new UnsupportedOperationException("EventBasedSingleThreadDemultiplexer can't be woken up")
}
