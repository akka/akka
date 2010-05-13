/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.concurrent.LinkedBlockingQueue
import java.util.Queue

import se.scalablesolutions.akka.actor.{Actor, ActorRef, ActorMessageInvoker}

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher private[akka] (val name: String, val messageHandler: MessageInvoker) 
  extends MessageDispatcher {
  
  def this(actor: Actor) = this(actor.getClass.getName, new ActorMessageInvoker(Actor.newActor(() => actor)))

  private val queue = new BlockingMessageQueue(name)
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false

  def dispatch(invocation: MessageInvocation) = queue.append(invocation) 

  def start = if (!active) {
    active = true
    selectorThread = new Thread {
      override def run = {
        while (active) {
          try {
            messageHandler.invoke(queue.take)
          } catch { case e: InterruptedException => active = false }
        }
      }
    }
    selectorThread.start
  }
                       
  def isShutdown = !active

  def usesActorMailbox = false

  def shutdown = if (active) {
    log.debug("Shutting down ThreadBasedDispatcher [%s]", name)
    active = false
    selectorThread.interrupt
    references.clear
  }
}

class BlockingMessageQueue(name: String) extends MessageQueue {
  // FIXME: configure the LinkedBlockingQueue in BlockingMessageQueue, use a Builder like in the ReactorBasedThreadPoolEventDrivenDispatcher
  private val queue = new LinkedBlockingQueue[MessageInvocation]
  def append(invocation: MessageInvocation) = queue.put(invocation)
  def take: MessageInvocation = queue.take
  def read(destination: Queue[MessageInvocation]) = throw new UnsupportedOperationException
  def interrupt = throw new UnsupportedOperationException
}
