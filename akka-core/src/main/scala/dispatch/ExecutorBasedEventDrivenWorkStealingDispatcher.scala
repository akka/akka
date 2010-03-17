/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import scala.collection.jcl._
import se.scalablesolutions.akka.actor.Actor

/**
 * An executor based event driven dispatcher which will try to redistribute work from busy actors to idle actors. It is assumed
 * that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors. I.e. the
 * actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.
 * <p/>
 * Although the technique used in this implementation is commonly known as "work stealing", the actual implementation is probably
 * best described as "work donating" because the actor of which work is being stolen takes the initiative.
 * <p/>
 * This dispatcher attempts to redistribute work between actors each time a message is dispatched on a busy actor. Work
 * will not be redistributed when actors are busy, but no new messages are dispatched.
 * TODO: it would be nice to be able to redistribute work even when no new messages are being dispatched, without impacting dispatching performance ?!
 * <p/>
 * The preferred way of creating dispatchers is to use
 * the {@link se.scalablesolutions.akka.dispatch.Dispatchers} factory object.
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
              tryDonateAndProcessMessages(invocation.receiver, thief)
            }
            case None => { /* no other actor in the pool */ }
          }
        }
      }
    })
  } else throw new IllegalStateException("Can't submit invocations to dispatcher since it's not started")

  /**
   * Try processing the mailbox of the given actor. Fails if the dispatching lock on the actor is already held by
   * another thread (because then that thread is already processing the mailbox).
   *
   * @return true if the mailbox was processed, false otherwise
   */
  private def tryProcessMailbox(receiver: Actor): Boolean = {
    var lockAcquiredOnce = false
    // this do-wile loop is required to prevent missing new messages between the end of processing
    // the mailbox and releasing the lock
    do {
      if (receiver._dispatcherLock.tryLock) {
        lockAcquiredOnce = true
        try {
          processMailbox(receiver)
        } finally {
          receiver._dispatcherLock.unlock
        }
      }
    } while ((lockAcquiredOnce && !receiver._mailbox.isEmpty))

    return lockAcquiredOnce
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
    // TODO: round robin, don't always start with first actor in the list
    for (actor <- new MutableIterator.Wrapper(references.values.iterator)) {
      if (actor != receiver) { // skip ourselves
        if (actor._mailbox.isEmpty) { // only pick actors which will most likely be able to process the messages
          return Some(actor)
        }
      }
    }
    return None
  }

  /**
   * Try donating messages to the thief and processing the thiefs mailbox. Doesn't do anything if we can not acquire
   * the thiefs dispatching lock, because in that case another thread is already processing the thiefs mailbox.
   */
  private def tryDonateAndProcessMessages(receiver: Actor, thief: Actor) = {
    if (thief._dispatcherLock.tryLock) {
      try {
        donateAndProcessMessages(receiver, thief)
      } finally {
        thief._dispatcherLock.unlock
      }
    }
  }

  /**
   * Donate messages to the thief and process them on the thief as long as the receiver has more messages.
   */
  private def donateAndProcessMessages(receiver: Actor, thief: Actor): Unit = {
    donateMessage(receiver, thief) match {
      case None => {
        // no more messages to donate
        return
      }
      case Some(donatedInvocation) => {
        processMailbox(thief)
        return donateAndProcessMessages(receiver, thief)
      }
    }
  }

  /**
   * Steal a message from the receiver and give it to the thief.
   */
  private def donateMessage(receiver: Actor, thief: Actor): Option[MessageInvocation] = {
    val donated = receiver._mailbox.pollLast
    if (donated != null) {
      //TODO: forward seems to fail from time to time ?!
      //thief.forward(donated.message)(Some(donated.receiver))
      thief.send(donated.message)
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
