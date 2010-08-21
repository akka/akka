/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.concurrent.LinkedBlockingQueue
import java.util.Queue

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.Config.config

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher(private val actor: ActorRef, val mailboxCapacity: Int = Dispatchers.MAILBOX_CAPACITY) extends MessageDispatcher {
  def this(actor: ActorRef) = this(actor, Dispatchers.MAILBOX_CAPACITY)// For Java

  private val name = actor.getClass.getName + ":" + actor.uuid
  private val threadName = "akka:thread-based:dispatcher:" + name
  private val queue = new BlockingMessageQueue(name, mailboxCapacity)
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false

  def dispatch(invocation: MessageInvocation) = queue.append(invocation)

  def start = if (!active) {
    log.debug("Starting up %s", toString)
    active = true
    selectorThread = new Thread(threadName) {
      override def run = {
        while (active) {
          try {
            actor.invoke(queue.take)
          } catch { case e: InterruptedException => active = false }
        }
      }
    }
    selectorThread.start
  }

  def isShutdown = !active

  def shutdown = if (active) {
    log.debug("Shutting down %s", toString)
    active = false
    selectorThread.interrupt
    references.clear
  }

  override def toString = "ThreadBasedDispatcher[" + threadName + "]"
}

// FIXME: configure the LinkedBlockingQueue in BlockingMessageQueue, use a Builder like in the ReactorBasedThreadPoolEventDrivenDispatcher
class BlockingMessageQueue(name: String, mailboxCapacity: Int) extends MessageQueue {
  private val queue = if (mailboxCapacity > 0) new LinkedBlockingQueue[MessageInvocation](mailboxCapacity)
                      else new LinkedBlockingQueue[MessageInvocation]
  def append(invocation: MessageInvocation) = queue.put(invocation)
  def take: MessageInvocation = queue.take
  def read(destination: Queue[MessageInvocation]) = throw new UnsupportedOperationException
  def interrupt = throw new UnsupportedOperationException
}
