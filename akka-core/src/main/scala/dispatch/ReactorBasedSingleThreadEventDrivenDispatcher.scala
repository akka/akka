/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

/**
 * Implements the Reactor pattern as defined in: [http://www.cs.wustl.edu/~schmidt/PDF/reactor-siemens.pdf].
 * See also this article: [http://today.java.net/cs/user/print/a/350].
 *
 * Based on code from the actorom actor framework by Sergio Bossa [http://code.google.com/p/actorom/].
 */
package se.scalablesolutions.akka.dispatch

import java.util.{LinkedList, List}

class ReactorBasedSingleThreadEventDrivenDispatcher(_name: String)
  extends AbstractReactorBasedEventDrivenDispatcher("akka:event-driven:reactor:single-thread:dispatcher:" + _name) {
  
  def start = if (!active) {
    log.debug("Starting up %s", toString)
    active = true
    val messageDemultiplexer = new Demultiplexer(queue)
    selectorThread = new Thread(name) {
      override def run = {
        while (active) {
          try {
            messageDemultiplexer.select
          } catch { case e: InterruptedException => active = false }
          val selectedInvocations = messageDemultiplexer.acquireSelectedInvocations
          val iter = selectedInvocations.iterator
          while (iter.hasNext) {
            val invocation = iter.next
            val invoker = messageInvokers.get(invocation.receiver)
            if (invoker ne null) invoker.invoke(invocation)
            iter.remove
          }
        }
      }
    }
    selectorThread.start
  }

  def isShutdown = !active

  def usesActorMailbox = false

  override def toString = "ReactorBasedSingleThreadEventDrivenDispatcher[" + name + "]"

  class Demultiplexer(private val messageQueue: ReactiveMessageQueue) extends MessageDemultiplexer {

    private val selectedQueue: List[MessageInvocation] = new LinkedList[MessageInvocation]

    def select = messageQueue.read(selectedQueue)

    def acquireSelectedInvocations: List[MessageInvocation] = selectedQueue

    def releaseSelectedInvocations = throw new UnsupportedOperationException("Demultiplexer can't release its queue")

    def wakeUp = throw new UnsupportedOperationException("Demultiplexer can't be woken up")
  }
}

