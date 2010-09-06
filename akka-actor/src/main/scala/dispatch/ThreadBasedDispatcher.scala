/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.Queue

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.Config.config
import concurrent.forkjoin.{TransferQueue, LinkedTransferQueue}
import java.util.concurrent.{BlockingQueue, TimeUnit, LinkedBlockingQueue}

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher(private val actor: ActorRef,
                            val mailboxBounds: BoundedMailbox
                            ) extends MessageDispatcher {
  def this(actor: ActorRef, capacity: Int) = this(actor,BoundedMailbox(capacity,None))
  def this(actor: ActorRef) = this(actor, Dispatchers.MAILBOX_CAPACITY)// For Java

  private val name = actor.getClass.getName + ":" + actor.uuid
  private val threadName = "akka:thread-based:dispatcher:" + name
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false

  override def createMailbox(actorRef: ActorRef): AnyRef = {
    if (mailboxBounds.capacity <= 0)
      new LinkedTransferQueue[MessageInvocation] with ThreadMessageBlockingQueue
    else if (mailboxBounds.pushTimeOut.isDefined) {
      val timeout = mailboxBounds.pushTimeOut.get
      new BoundedTransferQueue[MessageInvocation](mailboxBounds.capacity, timeout.length, timeout.unit) with ThreadMessageBlockingQueue
    }
    else
      new LinkedBlockingQueue[MessageInvocation](mailboxBounds.capacity) with ThreadMessageBlockingQueue
  }

  override def register(actorRef: ActorRef) = {
    if(actorRef != actor)
      throw new IllegalArgumentException("Cannot register to anyone but " + actor)

    super.register(actorRef)
  }

  def mailbox = actor.mailbox.asInstanceOf[ThreadMessageBlockingQueue]

  def mailboxSize(a: ActorRef) = mailbox.size

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

trait ThreadMessageBlockingQueue extends MessageQueue with BlockingQueue[MessageInvocation] {
  final def next: MessageInvocation = take
  def append(invocation: MessageInvocation): Unit = put(invocation)
}

trait ThreadMessageTransferQueue extends ThreadMessageBlockingQueue with TransferQueue[MessageInvocation] {
  final override def append(invocation: MessageInvocation): Unit = {
    if(!tryTransfer(invocation)) { //First, try to send the invocation to a waiting consumer
      if(!offer(invocation))       //If no consumer found, append it to the queue, if that fails, we're aborting
        throw new MessageQueueAppendFailedException("BlockingMessageTransferQueue transfer timed out")
    }
  }
}
