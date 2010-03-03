/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import scala.collection.jcl.MutableIterator.Wrapper
import se.scalablesolutions.akka.actor.Actor
import java.util.concurrent.ConcurrentHashMap

/**
 * TODO: doc
 * TODO: make sure everything in the pool is the same type of actor
 *
 * @author Jan Van Besien
 */
class ExecutorBasedEventDrivenWorkStealingDispatcher(_name: String) extends MessageDispatcher with ThreadPoolBuilder {
  @volatile private var active: Boolean = false

  // TODO: how to construct this name
  val name: String = "event-driven-work-stealing:executor:dispatcher:" + _name
  init

  def dispatch(invocation: MessageInvocation) = if (active) {
    // TODO: detect blocking with trylock ?! -> good idea... lets try that
    executor.execute(new Runnable() {
      def run = {
        processMailbox(invocation)
        stealAndScheduleWork(invocation.receiver)
      }
    })
  } else throw new IllegalStateException("Can't submit invocations to dispatcher since it's not started")

  /**
   * Process the messages in the mailbox of the receiver of the invocation.
   */
  private def processMailbox(invocation: MessageInvocation) = {
    val lockAcquired = invocation.receiver.lock.tryLock
    if (lockAcquired) {
      log.debug("[%s] has acquired lock for [%s] in [%s]", invocation.receiver, invocation.message, Thread.currentThread.getName)
      try {
        var messageInvocation = invocation.receiver._mailbox.poll
        while (messageInvocation != null) {
          log.debug("[%s] is processing [%s] in [%s]", invocation.receiver, messageInvocation.message, Thread.currentThread.getName)
          messageInvocation.invoke
          messageInvocation = invocation.receiver._mailbox.poll
        }
      } finally {
        invocation.receiver.lock.unlock
      }
    } else {
      // lock not acquired -> other dispatcher was busy -> no need to do anything
      log.debug("[%s] has NOT acquired lock for [%s] in [%s]", invocation.receiver, invocation.message, Thread.currentThread.getName)
    }
  }

  /**
   * Help another busy actor in the pool by stealing some work from its queue and dispatching it on the actor
   * we were being invoked for (because we are done with the mailbox messages).
   */
  private def stealAndScheduleWork(thief: Actor) = {
    tryStealWork(thief).foreach {
      invocation => {
        log.debug("[%s] has stolen work [%s] in [%s]", thief, invocation.message, Thread.currentThread.getName)
        thief.send(invocation.message)
        // TODO: thief.forward(invocation.message)(invocation.sender) (doesn't work?)
      }
    }
  }

  def tryStealWork(thief: Actor): Option[MessageInvocation] = {
    // TODO: functional style?
    //    log.debug("[%s] is trying to steal work in [%s] from one of [%s]", thief, Thread.currentThread.getName, references.values)
    for (actor <- new Wrapper(references.values.iterator)) {
      if (actor != thief) {
        val stolenWork: MessageInvocation = actor._mailbox.pollLast
        if (stolenWork != null)
          return Some(stolenWork)
      }
    }
    return None
  }


  override def register(actor: Actor) = {
    super.register(actor)
    executor.execute(new Runnable() {
      def run = {
        stealAndScheduleWork(actor)
      }
    })
    actor // TODO: why is this necessary?
  }

  def start = if (!active) {
    active = true
  }

  def shutdown = if (active) {
    log.debug("Shutting down ThreadBasedDispatcher [%s]", name)
    executor.shutdownNow
    active = false
    references.clear
  }

  def ensureNotActive: Unit = if (active) throw new IllegalStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")

  private[akka] def init = withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity.buildThreadPool
}