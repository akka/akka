/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch


import akka.actor.{Actor, ActorRef, IllegalActorStateException}
import akka.util.Switch

import java.util.concurrent. {ExecutorService, CopyOnWriteArrayList}
import java.util.concurrent.atomic.AtomicReference

import jsr166x.{Deque, ConcurrentLinkedDeque, LinkedBlockingDeque}

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
 * the {@link akka.dispatch.Dispatchers} factory object.
 *
 * @see akka.dispatch.ExecutorBasedEventDrivenWorkStealingDispatcher
 * @see akka.dispatch.Dispatchers
 *
 * @author Jan Van Besien
 */
class ExecutorBasedEventDrivenWorkStealingDispatcher(
  _name: String,
  _mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  config: ThreadPoolConfig = ThreadPoolConfig()) extends MessageDispatcher {

  def this(_name: String, mailboxType: MailboxType) = this(_name, mailboxType,ThreadPoolConfig())

  def this(_name: String) = this(_name, Dispatchers.MAILBOX_TYPE,ThreadPoolConfig())

  //implicit def actorRef2actor(actorRef: ActorRef): Actor = actorRef.actor

  val mailboxType = Some(_mailboxType)
  val name = "akka:event-driven-work-stealing:dispatcher:" + _name

  /** Type of the actors registered in this dispatcher. */
  @volatile private var actorType: Option[Class[_]] = None
  private val pooledActors = new CopyOnWriteArrayList[ActorRef]
  private[akka] val threadFactory = new MonitorableThreadFactory(name)
  private[akka] val executorService = new AtomicReference[ExecutorService](config.createLazyExecutorService(threadFactory))

  /** The index in the pooled actors list which was last used to steal work */
  @volatile private var lastThiefIndex = 0

  /**
   * @return the mailbox associated with the actor
   */
  private def getMailbox(receiver: ActorRef) = receiver.mailbox.asInstanceOf[Deque[MessageInvocation] with MessageQueue with Runnable]

  override def mailboxSize(actorRef: ActorRef) = getMailbox(actorRef).size

  private[akka] def dispatch(invocation: MessageInvocation) {
    val mbox = getMailbox(invocation.receiver)
    mbox enqueue invocation
    executorService.get() execute mbox
  }

  /**
   * Try processing the mailbox of the given actor. Fails if the dispatching lock on the actor is already held by
   * another thread (because then that thread is already processing the mailbox).
   *
   * @return true if the mailbox was processed, false otherwise
   */
  private def tryProcessMailbox(mailbox: MessageQueue): Boolean = {
    var mailboxWasProcessed = false

    // this do-wile loop is required to prevent missing new messages between the end of processing
    // the mailbox and releasing the lock
    do {
      if (mailbox.dispatcherLock.tryLock) {
        try {
          mailboxWasProcessed = processMailbox(mailbox)
        } finally {
          mailbox.dispatcherLock.unlock
        }
      }
    } while ((mailboxWasProcessed && !mailbox.isEmpty))

    mailboxWasProcessed
  }

  /**
   * Process the messages in the mailbox of the given actor.
   * @return
   */
  private def processMailbox(mailbox: MessageQueue): Boolean = try {
    if (mailbox.suspended.isOn)
        return false

    var messageInvocation = mailbox.dequeue
    while (messageInvocation ne null) {
      messageInvocation.invoke
      if (mailbox.suspended.isOn)
        return false
      messageInvocation = mailbox.dequeue
    }
    true
  } catch {
    case ie: InterruptedException => false
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
      if (actor != receiver && getMailbox(actor).isEmpty) return (Some(actor), index)
    }
    (None, startIndex) // nothing found, reuse same start index next time
  }

  /**
   * Try donating messages to the thief and processing the thiefs mailbox. Doesn't do anything if we can not acquire
   * the thiefs dispatching lock, because in that case another thread is already processing the thiefs mailbox.
   */
  private def tryDonateAndProcessMessages(receiver: ActorRef, thief: ActorRef) = {
    val mailbox = getMailbox(thief)
    if (mailbox.dispatcherLock.tryLock) {
      try {
        while(donateMessage(receiver, thief)) processMailbox(mailbox)
      } finally {
        mailbox.dispatcherLock.unlock
      }
    }
  }

  /**
   * Steal a message from the receiver and give it to the thief.
   */
  private def donateMessage(receiver: ActorRef, thief: ActorRef): Boolean = {
    val donated = getMailbox(receiver).pollLast
    if (donated ne null) {
      if (donated.senderFuture.isDefined) thief.postMessageToMailboxAndCreateFutureResultWithTimeout[Any](
        donated.message, receiver.timeout, donated.sender, donated.senderFuture)
      else if (donated.sender.isDefined) thief.postMessageToMailbox(donated.message, donated.sender)
      else thief.postMessageToMailbox(donated.message, None)
      true
    } else false
  }

  private[akka] def start = log.slf4j.debug("Starting up {}",toString)

  private[akka] def shutdown {
    val old = executorService.getAndSet(config.createLazyExecutorService(threadFactory))
    if (old ne null) {
      log.slf4j.debug("Shutting down {}", toString)
      old.shutdownNow()
    }
  }


  def suspend(actorRef: ActorRef) {
    getMailbox(actorRef).suspended.switchOn
  }

  def resume(actorRef: ActorRef) {
    val mbox = getMailbox(actorRef)
    mbox.suspended.switchOff
    executorService.get() execute mbox
  }

  override val toString = "ExecutorBasedEventDrivenWorkStealingDispatcher[" + name + "]"

  private[akka] def createTransientMailbox(actorRef: ActorRef, mailboxType: TransientMailbox): AnyRef = mailboxType match {
    case UnboundedMailbox(blocking) => // FIXME make use of 'blocking' in work stealer ConcurrentLinkedDeque
      new ConcurrentLinkedDeque[MessageInvocation] with MessageQueue with Runnable {
        def enqueue(handle: MessageInvocation): Unit = this.add(handle)

        def dequeue: MessageInvocation = this.poll()

        def run = if (!tryProcessMailbox(this)) {
          // we are not able to process our mailbox (another thread is busy with it), so lets donate some of our mailbox
          // to another actor and then process his mailbox in stead.
          findThief(actorRef).foreach( tryDonateAndProcessMessages(actorRef,_) )
        }
      }
    case BoundedMailbox(blocking, capacity, pushTimeOut) =>
      new LinkedBlockingDeque[MessageInvocation](capacity) with MessageQueue with Runnable {
        def enqueue(handle: MessageInvocation): Unit = this.add(handle)

        def dequeue: MessageInvocation = this.poll()

        def run = if (!tryProcessMailbox(this)) {
          // we are not able to process our mailbox (another thread is busy with it), so lets donate some of our mailbox
          // to another actor and then process his mailbox in stead.
          findThief(actorRef).foreach( tryDonateAndProcessMessages(actorRef, _) )
        }
      }
  }

  /**
   * Creates and returns a durable mailbox for the given actor.
   */
  private[akka] def createDurableMailbox(actorRef: ActorRef, mailboxType: DurableMailbox): AnyRef =
    createMailbox(mailboxType.mailboxImplClassname, actorRef)

  private[akka] override def register(actorRef: ActorRef) = {
    verifyActorsAreOfSameType(actorRef)
    pooledActors add actorRef
    super.register(actorRef)
  }

  private[akka] override def unregister(actorRef: ActorRef) = {
    pooledActors remove actorRef
    super.unregister(actorRef)
  }

  private def verifyActorsAreOfSameType(actorOfId: ActorRef) = {
    actorType match {
      case None => actorType = Some(actorOfId.actor.getClass)
      case Some(aType) =>
        if (aType != actorOfId.actor.getClass)
          throw new IllegalActorStateException(String.format(
            "Can't register actor {} in a work stealing dispatcher which already knows actors of type {}",
            actorOfId.actor, aType))
    }
  }
}
