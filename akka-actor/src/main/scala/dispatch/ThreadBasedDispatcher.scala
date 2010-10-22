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
class ThreadBasedDispatcher(private val actor: ActorRef, _mailboxType: MailboxType) 
  extends ExecutorBasedEventDrivenDispatcher(
    actor.getClass.getName + ":" + actor.uuid,
    Dispatchers.THROUGHPUT,
    -1,
    _mailboxType,
    ThreadBasedDispatcher.oneThread) {

  def this(actor: ActorRef) = this(actor, BoundedMailbox(true)) // For Java API

  def this(actor: ActorRef, capacity: Int) = this(actor, BoundedMailbox(true, capacity))

  def this(actor: ActorRef, capacity: Int, pushTimeOut: Duration) = this(actor, BoundedMailbox(true, capacity, pushTimeOut))

  override def register(actorRef: ActorRef) = {
    if (actorRef != actor) throw new IllegalArgumentException("Cannot register to anyone but " + actor)
    super.register(actorRef)
  }
}

object ThreadBasedDispatcher {
  val oneThread: ThreadPoolConfig = ThreadPoolConfig(allowCorePoolTimeout = true, corePoolSize = 1, maxPoolSize = 1)
}

