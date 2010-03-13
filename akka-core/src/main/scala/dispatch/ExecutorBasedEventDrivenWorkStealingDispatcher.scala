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
 * the  { @link se.scalablesolutions.akka.dispatch.Dispatchers } factory object.
 * <p/>
 * Although the technique used in this implementation is commonly known as "work stealing", the actual implementation is probably
 * best described as "work donating" because the actor of which work is being stolen takes the initiative.
 *
 * TODO: make sure everything in the pool is the same type of actor
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
        if (!tryProcessMailbox(invocation.receiver)) {
          // we are not able to process our mailbox (another thread is busy with it), so lets donate some of our mailbox
          // to another actor and then process his mailbox in stead.
          findThief(invocation.receiver) match {
            case Some(thief) => {
              // TODO: maybe do the donation with the lock held, to prevent donating messages that will not be processed
              donateMessage(invocation.receiver, thief) match {
                case Some(donatedInvocation) => {
                  tryProcessMailbox(thief)
                }
                case None => { /* no messages left to donate */ }
              }
            }
            case None => { /* no other actor in the pool */ }
          }
        }
      }
    })
  } else throw new IllegalStateException("Can't submit invocations to dispatcher since it's not started")

  /**
   * Try processing the mailbox of the given actor. Fails if the dispatching lock on the actor is already held by
   * another thread.
   *
   * @return true if the mailbox was processed, false otherwise
   */
  private def tryProcessMailbox(receiver: Actor): Boolean = {
    if (receiver._dispatcherLock.tryLock) {
      // Only dispatch if we got the lock. Otherwise another thread is already dispatching.
      try {
        processMailbox(receiver)
      } finally {
        receiver._dispatcherLock.unlock
      }
      return true
    } else return false
  }

  /**
   * Process the messages in the mailbox of the given actor.
   */
  private def processMailbox(receiver: Actor) = {
    var messageInvocation = receiver._mailbox.poll
    while (messageInvocation != null) {
      messageInvocation.invoke
      messageInvocation = receiver._mailbox.poll
    }
  }

  private def findThief(receiver: Actor): Option[Actor] = {
    // TODO: round robin or random?
    for (actor <- new Wrapper(references.values.iterator)) {
      if (actor != receiver) { // skip ourselves
        return Some(actor)
      }
    }
    return None
  }

  /**
   * Steal a message from the receiver and give it to the thief.
   */
  private def donateMessage(receiver: Actor, thief: Actor): Option[MessageInvocation] = {
    val donated = receiver._mailbox.pollLast
    if (donated != null) {
      //log.debug("donating %s from %s to %s", donated.message, receiver, thief)
      thief.forward(donated.message)(Some(donated.receiver))
      return Some(donated)
    } else return None
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
