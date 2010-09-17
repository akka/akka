/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.Queue

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.Config.config
import concurrent.forkjoin.{TransferQueue, LinkedTransferQueue}
import java.util.concurrent.{ConcurrentLinkedQueue, BlockingQueue, TimeUnit, LinkedBlockingQueue}

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher(private val actor: ActorRef,
                            val mailboxConfig: MailboxConfig
                            ) extends MessageDispatcher {
  def this(actor: ActorRef, capacity: Int) = this(actor,MailboxConfig(capacity,None,true))
  def this(actor: ActorRef) = this(actor, Dispatchers.MAILBOX_CAPACITY)// For Java

  private val name = actor.getClass.getName + ":" + actor.uuid
  private val threadName = "akka:thread-based:dispatcher:" + name
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false

  override def createMailbox(actorRef: ActorRef): AnyRef = mailboxConfig.newMailbox(blockDequeue = true)
  
  override def register(actorRef: ActorRef) = {
    if(actorRef != actor)
      throw new IllegalArgumentException("Cannot register to anyone but " + actor)

    super.register(actorRef)
  }

  def mailbox = actor.mailbox.asInstanceOf[Queue[MessageInvocation] with MessageQueue]

  def mailboxSize(a: ActorRef) = mailbox.size

  def dispatch(invocation: MessageInvocation) = mailbox enqueue invocation

  def start = if (!active) {
    log.debug("Starting up %s", toString)
    active = true
    selectorThread = new Thread(threadName) {
      override def run = {
        while (active) {
          try {
            actor.invoke(mailbox.dequeue)
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