/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.concurrent.LinkedBlockingQueue
import java.util.Queue

import se.scalablesolutions.akka.actor.{Actor, ActorRef}

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher(private val actor: ActorRef) extends MessageDispatcher {
  private val name = actor.getClass.getName + ":" + actor.uuid
  private val threadName = "akka:thread-based:dispatcher:" + name
  private val queue = new BlockingMessageQueue(name)
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

  def usesActorMailbox = false

  def shutdown = if (active) {
    log.debug("Shutting down %s", toString)
    active = false
    selectorThread.interrupt
    references.clear
  }

  override def toString = "ThreadBasedDispatcher[" + threadName + "]"
}

class BlockingMessageQueue(name: String) extends MessageQueue {
  // FIXME: configure the LinkedBlockingQueue in BlockingMessageQueue, use a Builder like in the ReactorBasedThreadPoolEventDrivenDispatcher
  private val queue = new LinkedBlockingQueue[MessageInvocation]
  def append(invocation: MessageInvocation) = queue.put(invocation)
  def take: MessageInvocation = queue.take
  def read(destination: Queue[MessageInvocation]) = throw new UnsupportedOperationException
  def interrupt = throw new UnsupportedOperationException
}
