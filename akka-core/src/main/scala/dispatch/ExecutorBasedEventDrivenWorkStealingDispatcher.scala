/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.concurrent.CopyOnWriteArrayList

import se.scalablesolutions.akka.actor.{Actor, ActorRef, IllegalActorStateException}

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
 * @see se.scalablesolutions.akka.dispatch.ExecutorBasedEventDrivenWorkStealingDispatcher
 * @see se.scalablesolutions.akka.dispatch.Dispatchers
 *
 * @author Jan Van Besien
 */
class ExecutorBasedEventDrivenWorkStealingDispatcher(_name: String) extends MessageDispatcher with ThreadPoolBuilder {
  @volatile private var active: Boolean = false

  implicit def actorRef2actor(actorRef: ActorRef): Actor = actorRef.actor

  /** Type of the actors registered in this dispatcher. */
  private var actorType:Option[Class[_]] = None

  private val pooledActors = new CopyOnWriteArrayList[ActorRef]

  /** The index in the pooled actors list which was last used to steal work */
  @volatile private var lastThiefIndex = 0

  val name = "akka:event-driven-work-stealing:dispatcher:" + _name
  init

  def dispatch(invocation: MessageInvocation) = if (active) {
    executor.execute(new Runnable() {
      def run = {
        if (!tryProcessMailbox(invocation.receiver)) {
          // we are not able to process our mailbox (another thread is busy with it), so lets donate some of our mailbox
          // to another actor and then process his mailbox in stead.
          findThief(invocation.receiver).foreach( tryDonateAndProcessMessages(invocation.receiver,_) )
        }
      }
    })
  } else throw new IllegalActorStateException("Can't submit invocations to dispatcher since it's not started")

  /**
   * Try processing the mailbox of the given actor. Fails if the dispatching lock on the actor is already held by
   * another thread (because then that thread is already processing the mailbox).
   *
   * @return true if the mailbox was processed, false otherwise
   */
  private def tryProcessMailbox(receiver: ActorRef): Boolean = {
    var lockAcquiredOnce = false
    val lock = receiver.dispatcherLock
    // this do-wile loop is required to prevent missing new messages between the end of processing
    // the mailbox and releasing the lock
    do {
      if (lock.tryLock) {
        lockAcquiredOnce = true
        try {
          processMailbox(receiver)
        } finally {
          lock.unlock
        }
      }
    } while ((lockAcquiredOnce && !receiver.mailbox.isEmpty))

    return lockAcquiredOnce
  }

  /**
   * Process the messages in the mailbox of the given actor.
   */
  private def processMailbox(receiver: ActorRef) = {
    var messageInvocation = receiver.mailbox.poll
    while (messageInvocation != null) {
      messageInvocation.invoke
      messageInvocation = receiver.mailbox.poll
    }
  }

  private def findThief(receiver: ActorRef): Option[ActorRef] = {
    // copy to prevent concurrent modifications having any impact
    val actors = pooledActors.toArray(new Array[ActorRef](pooledActors.size))
    val i = if ( lastThiefIndex > actors.size ) 0 else lastThiefIndex

    // we risk to pick a thief which is unregistered from the dispatcher in the meantime, but that typically means
    // the dispatcher is being shut down...
    val (thief: Option[ActorRef], index: Int) = doFindThief(receiver, actors, i)
    lastThiefIndex = (index + 1) % actors.size
    thief
  }

  /**
   * Find a thief to process the receivers messages from the given list of actors.
   *
   * @param receiver original receiver of the message
   * @param actors list of actors to find a thief in
   * @param startIndex first index to start looking in the list (i.e. for round robin)
   * @return the thief (or None) and the new index to start searching next time
   */
  private def doFindThief(receiver: ActorRef, actors: Array[ActorRef], startIndex: Int): (Option[ActorRef], Int) = {
    for (i <- 0 to actors.length) {
      val index = (i + startIndex) % actors.length
      val actor = actors(index)
      if (actor != receiver && actor.mailbox.isEmpty) return (Some(actor), index)
    }
    (None, startIndex) // nothing found, reuse same start index next time
  }

  /**
   * Try donating messages to the thief and processing the thiefs mailbox. Doesn't do anything if we can not acquire
   * the thiefs dispatching lock, because in that case another thread is already processing the thiefs mailbox.
   */
  private def tryDonateAndProcessMessages(receiver: ActorRef, thief: ActorRef) = {
    if (thief.dispatcherLock.tryLock) {
      try {
        while(donateMessage(receiver, thief)) processMailbox(thief)
      } finally {
        thief.dispatcherLock.unlock
      }
    }
  }

  /**
   * Steal a message from the receiver and give it to the thief.
   */
  private def donateMessage(receiver: ActorRef, thief: ActorRef): Boolean = {
    val donated = receiver.mailbox.pollLast
    if (donated ne null) {
      if (donated.senderFuture.isDefined) thief.self.postMessageToMailboxAndCreateFutureResultWithTimeout[Any](
        donated.message, receiver.timeout, donated.sender, donated.senderFuture)
      else if (donated.sender.isDefined) thief.self.postMessageToMailbox(donated.message, donated.sender)
      else thief.self.postMessageToMailbox(donated.message, None)
      true
    } else false
  }

  def start = if (!active) {
    active = true
  }

  def shutdown = if (active) {
    log.debug("Shutting down %s", toString)
    executor.shutdownNow
    active = false
    references.clear
  }

  def ensureNotActive(): Unit = if (active) throw new IllegalActorStateException(
    "Can't build a new thread pool for a dispatcher that is already up and running")

  override def toString = "ExecutorBasedEventDrivenWorkStealingDispatcher[" + name + "]"
 
  private[akka] def init = withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity.buildThreadPool

  override def register(actorRef: ActorRef) = {
    verifyActorsAreOfSameType(actorRef)
    pooledActors.add(actorRef)
    super.register(actorRef)
  }

  override def unregister(actorRef: ActorRef) = {
    pooledActors.remove(actorRef)
    super.unregister(actorRef)
  }

  def usesActorMailbox = true

  private def verifyActorsAreOfSameType(actorOfId: ActorRef) = {
    actorType match {
      case None => actorType = Some(actorOfId.actor.getClass)
      case Some(aType) =>
        if (aType != actorOfId.actor.getClass)
          throw new IllegalActorStateException(String.format(
            "Can't register actor %s in a work stealing dispatcher which already knows actors of type %s",
            actorOfId.actor, aType))
    }
  }
}
