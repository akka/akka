/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import scala.collection.jcl.MutableIterator.Wrapper
import se.scalablesolutions.akka.actor.Actor

/**
 * An executor based event driven dispatcher which will try to redistribute work from busy actors to idle actors. It is assumed
 * that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors. I.e. the
 * actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.
 * <p/>
 * The preferred way of creating dispatchers is to use
 * the   { @link se.scalablesolutions.akka.dispatch.Dispatchers } factory object.
 *
 *
 * TODO: make sure everything in the pool is the same type of actor
 *
 * TODO: Find a way to only send new work to an actor if that actor will actually be scheduled
 * immidiately afterwards. Otherwize the work gets a change of being stolen back again... which is not optimal.
 *
 * @see se.scalablesolutions.akka.dispatch.ExecutorBasedEventDrivenWorkStealingDispatcher
 * @see se.scalablesolutions.akka.dispatch.Dispatchers
 *
 * @author Jan Van Besien
 */
class ExecutorBasedEventDrivenWorkStealingDispatcher(_name: String) extends MessageDispatcher with ThreadPoolBuilder {
  @volatile private var active: Boolean = false

  // TODO: is there a naming convention for this name?
  val name: String = "event-driven-work-stealing:executor:dispatcher:" + _name
  init

  def dispatch(invocation: MessageInvocation) = if (active) {
    executor.execute(new Runnable() {
      def run = {
        val lockedForDispatching = invocation.receiver._dispatcherLock.tryLock
        if (lockedForDispatching) {
          // Only dispatch if we got the lock. Otherwise another thread is already dispatching.
          try {
            processMailbox(invocation.receiver)
          } finally {
            invocation.receiver._dispatcherLock.unlock
          }
        } else {
          // we have the thread, but we can not do anything with our actor -> donate work to another actor, and process that actor here
          var thief: Option[Actor] = None
          for (actor <- new Wrapper(references.values.iterator)) {
            if (actor != invocation.receiver) { // skip ourselves
              thief = Some(actor)
            }
          }

          thief match {
            case None => {}
            case Some(t) => {
              // TODO: need to get the lock here!
              // donate new message to the mailbox of the thief
              val donated: MessageInvocation = invocation.receiver._mailbox.pollLast
              if (donated != null) {
                t.forward(donated.message)(Some(donated.receiver))
                // try processing it in situ, while we still  hold the thread
                val lockedTForDispatching = t._dispatcherLock.tryLock
                if (lockedTForDispatching) {
                  // Only dispatch if we got the lock. Otherwise another thread is already dispatching.
                  try {
                    processMailbox(t)
                  } finally {
                    t._dispatcherLock.unlock
                  }
                }
              }
            }
          }

        }
      }
    })
  } else throw new IllegalStateException("Can't submit invocations to dispatcher since it's not started")

  /**
   * Process the messages in the mailbox of the receiver of the invocation.
   */
  private def processMailbox(receiver: Actor) = {
    var messageInvocation = receiver._mailbox.poll
    while (messageInvocation != null) {
      messageInvocation.invoke
      messageInvocation = receiver._mailbox.poll
    }
  }

  //  /**
  //   * Help another busy actor in the pool by stealing some work from its queue and forwarding it to the actor
  //   * we were being invoked for (because we are done with the mailbox messages).
  //   */
  //  private def stealAndScheduleWork(thief: Actor) = {
  //    tryStealWork(thief).foreach {
  //      invocation => {
  //        log.debug("[%s] stole work [%s] from [%s]", thief, invocation.message, invocation.receiver)
  //        // as if the original receiving actor would forward it to the thief
  //        thief.forward(invocation.message)(Some(invocation.receiver))
  //      }
  //    }
  //  }
  //
  //  def tryStealWork(thief: Actor): Option[MessageInvocation] = {
  //    // TODO: use random or round robin scheme to not always steal from the same actor?
  //    for (actor <- new Wrapper(references.values.iterator)) {
  //      if (actor != thief) {
  //        val stolenWork: MessageInvocation = actor._mailbox.pollLast
  //        if (stolenWork != null)
  //          return Some(stolenWork)
  //      }
  //    }
  //
  //    // nothing found to steal
  //    return None
  //  }


  //  override def register(actor: Actor) = {
  //    super.register(actor)
  //    executor.execute(new Runnable() {
  //      def run = {
  //        stealAndScheduleWork(actor)
  //      }
  //    })
  //    actor // TODO: why is this necessary?
  //  }

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
