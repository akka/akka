/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.Queue

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.Config.config
import java.util.concurrent.{TimeUnit, LinkedBlockingQueue}
import concurrent.forkjoin.{TransferQueue, LinkedTransferQueue}

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher(private val actor: ActorRef,
                            val mailboxCapacity: Int = Dispatchers.MAILBOX_CAPACITY,
                            val pushTimeout: Long = 10000,
                            val pushTimeoutUnit: TimeUnit = TimeUnit.MILLISECONDS
                            ) extends MessageDispatcher {
  def this(actor: ActorRef) = this(actor, Dispatchers.MAILBOX_CAPACITY)// For Java

  private val name = actor.getClass.getName + ":" + actor.uuid
  private val threadName = "akka:thread-based:dispatcher:" + name
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false

  if (actor.mailbox eq null) {
    actor.mailbox = if (mailboxCapacity > 0)
                         new BoundedTransferQueue[MessageInvocation](mailboxCapacity,pushTimeout,pushTimeoutUnit) with ThreadMessageQueue
                       else
                         new LinkedTransferQueue[MessageInvocation] with ThreadMessageQueue
  }

  override def register(actorRef: ActorRef) = {
    if(actorRef != actor)
      throw new IllegalArgumentException("Cannot register to anyone but " + actor)

    super.register(actorRef)
  }

  def mailbox = actor.mailbox.asInstanceOf[ThreadMessageQueue]

  def dispatch(invocation: MessageInvocation) = mailbox append invocation

  def start = if (!active) {
    log.debug("Starting up %s", toString)
    active = true
    selectorThread = new Thread(threadName) {
      override def run = {
        while (active) {
          try {
            actor.invoke(mailbox.next)
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
    uuids.clear
  }

  override def toString = "ThreadBasedDispatcher[" + threadName + "]"
}

trait ThreadMessageQueue extends MessageQueue { self: TransferQueue[MessageInvocation] =>

  final def append(invocation: MessageInvocation): Unit = {
    if(!self.tryTransfer(invocation)) { //First, try to send the invocation to a waiting consumer
      if(!self.offer(invocation))       //If no consumer found, append it to the queue, if that fails, we're aborting
        throw new MessageQueueAppendFailedException("BlockingMessageTransferQueue transfer timed out")
    }
  }

  final def next: MessageInvocation = self.take
}
