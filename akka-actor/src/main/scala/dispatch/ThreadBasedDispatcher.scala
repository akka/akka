/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.Queue

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.Config.config
import concurrent.forkjoin.{TransferQueue, LinkedTransferQueue}
import java.util.concurrent.{ConcurrentLinkedQueue, BlockingQueue, TimeUnit, LinkedBlockingQueue}

object ThreadBasedDispatcher {
  def oneThread(b: ThreadPoolBuilder) {
    b setCorePoolSize 1
    b setMaxPoolSize 1
    b setAllowCoreThreadTimeout true
  }
}

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadBasedDispatcher(private val actor: ActorRef,
                            val mailboxConfig: MailboxConfig
                            ) extends ExecutorBasedEventDrivenDispatcher(
                                actor.getClass.getName + ":" + actor.uuid,
                                Dispatchers.THROUGHPUT,
                                -1,
                                mailboxConfig,
                                ThreadBasedDispatcher.oneThread) {
  def this(actor: ActorRef, capacity: Int) = this(actor,MailboxConfig(capacity,None,true))
  def this(actor: ActorRef) = this(actor, Dispatchers.MAILBOX_CAPACITY)// For Java
  
  override def register(actorRef: ActorRef) = {
    if(actorRef != actor)
      throw new IllegalArgumentException("Cannot register to anyone but " + actor)

    super.register(actorRef)
  }

  override def toString = "ThreadBasedDispatcher[" + name + "]"
}