/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.Config.config
import se.scalablesolutions.akka.util.Duration

import java.util.Queue
import java.util.concurrent.{ConcurrentLinkedQueue, BlockingQueue, TimeUnit, LinkedBlockingQueue}

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher(private val actor: ActorRef, _mailboxType: MailboxType) extends MessageDispatcher {

  def this(actor: ActorRef) = this(actor, BoundedMailbox(true)) // For Java API

  def this(actor: ActorRef, capacity: Int) = this(actor, BoundedMailbox(true, capacity))

  def this(actor: ActorRef, capacity: Int, pushTimeOut: Duration) = this(actor, BoundedMailbox(true, capacity, pushTimeOut))

  val mailboxType = Some(_mailboxType)

  private val name = actor.getClass.getName + ":" + actor.uuid
  private val threadName = "akka:thread-based:dispatcher:" + name
  private var selectorThread: Thread = _
  @volatile private var active: Boolean = false

  def createTransientMailbox(actorRef: ActorRef, mailboxType: TransientMailboxType): AnyRef = mailboxType match {
    case UnboundedMailbox(blocking) => 
      new DefaultUnboundedMessageQueue(blocking)
    case BoundedMailbox(blocking, capacity, pushTimeOut) => 
      new DefaultBoundedMessageQueue(capacity, pushTimeOut, blocking)
  }
  
  override def register(actorRef: ActorRef) = {
    if (actorRef != actor) throw new IllegalArgumentException("Cannot register to anyone but " + actor)
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