/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.actor.{ActorRef, Actor, IllegalActorStateException}
import akka.util.{ReflectiveAccess, Switch}

import java.util.Queue
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import java.util.concurrent.{ TimeUnit, ExecutorService, RejectedExecutionException, ConcurrentLinkedQueue, LinkedBlockingQueue}

/**
 * An executor based event driven dispatcher which will try to redistribute work from busy actors to idle actors. It is assumed
 * that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors. I.e. the
 * actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.
 * <p/>
 * Although the technique used in this implementation is commonly known as "work stealing", the actual implementation is probably
 * best described as "work donating" because the actor of which work is being stolen takes the initiative.
 * <p/>
 * The preferred way of creating dispatchers is to use
 * the {@link akka.dispatch.Dispatchers} factory object.
 *
 * @see akka.dispatch.ExecutorBasedEventDrivenWorkStealingDispatcher
 * @see akka.dispatch.Dispatchers
 *
 * @author Viktor Klang
 */
class ExecutorBasedEventDrivenWorkStealingDispatcher(
  _name: String,
  throughput: Int = Dispatchers.THROUGHPUT,
  throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  config: ThreadPoolConfig = ThreadPoolConfig(),
  val maxDonationQty: Int = Dispatchers.THROUGHPUT)
  extends ExecutorBasedEventDrivenDispatcher(_name, throughput, throughputDeadlineTime, mailboxType, config) {

  def this(_name: String, throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(_name, throughput, throughputDeadlineTime, mailboxType,ThreadPoolConfig())  // Needed for Java API usage

  def this(_name: String, throughput: Int, mailboxType: MailboxType) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(_name: String, throughput: Int) =
    this(_name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(_name: String, _config: ThreadPoolConfig) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, _config)

  def this(_name: String, memberType: Class[_ <: Actor]) =
    this(_name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  @volatile private var actorType: Option[Class[_]] = None
  @volatile private var members = Vector[ActorRef]()

  /** The index in the pooled actors list which was last used to steal work */
  private val lastDonorRecipient = new AtomicInteger(0)

  private[akka] override def register(actorRef: ActorRef) = {
    //Verify actor type conformity
    actorType match {
      case None => actorType = Some(actorRef.actor.getClass)
      case Some(aType) =>
        if (aType != actorRef.actor.getClass)
          throw new IllegalActorStateException(String.format(
            "Can't register actor %s in a work stealing dispatcher which already knows actors of type %s",
            actorRef, aType))
    }

    synchronized { members :+= actorRef } //Update members
    super.register(actorRef)
  }

  private[akka] override def unregister(actorRef: ActorRef) = {
    synchronized { members = members.filterNot(actorRef eq) } //Update members
    super.unregister(actorRef)
  }

  override private[akka] def reRegisterForExecution(mbox: MessageQueue with ExecutableMailbox): Unit = {
    donateFrom(mbox) //When we reregister, first donate messages to another actor
    if (!mbox.isEmpty) //If we still have messages left to process, reschedule for execution
      super.reRegisterForExecution(mbox)
  }
  
  private[akka] def donateFrom(donorMbox: MessageQueue with ExecutableMailbox): Unit = {
    val actors  = members // copy to prevent concurrent modifications having any impact
    val actorSz = actors.size
    val ldr     = lastDonorRecipient.get
    val i       = if ( ldr > actorSz ) 0 else ldr

    def doFindDonorRecipient(donorMbox: MessageQueue with ExecutableMailbox, potentialRecipients: Vector[ActorRef], startIndex: Int): ActorRef = {
      val prSz = potentialRecipients.size
      var i = 0
      var recipient: ActorRef = null
      while((i < prSz) && (recipient eq null)) {
        val index = (i + startIndex) % prSz //Wrap-around, one full lap
        val actor = potentialRecipients(index)
        val mbox = getMailbox(actor)

        if ((mbox ne donorMbox) && mbox.isEmpty) { //Don't donate to yourself
          lastDonorRecipient.set((index + 1) % actors.length)
          recipient = actor //Found!
        }

        i += 1
      }

      lastDonorRecipient.compareAndSet(ldr, (startIndex + 1) % actors.length)
      recipient // nothing found, reuse same start index next time
    }

    // we risk to pick a thief which is unregistered from the dispatcher in the meantime, but that typically means
    // the dispatcher is being shut down...
    val recipient = doFindDonorRecipient(donorMbox, actors, i)
    if (recipient ne null) {
        def tryDonate(): Boolean = {
          var organ = donorMbox.dequeue //FIXME switch to something that cannot block
          if (organ ne null) {
            println("DONATING!!!")
            if (organ.senderFuture.isDefined) recipient.postMessageToMailboxAndCreateFutureResultWithTimeout[Any](
              organ.message, recipient.timeout, organ.sender, organ.senderFuture)
            else if (organ.sender.isDefined) recipient.postMessageToMailbox(organ.message, organ.sender)
            else recipient.postMessageToMailbox(organ.message, None)
            true
          } else false
        }

      var donated = 0
      while(donated < maxDonationQty && tryDonate())
        donated += 1
    }
  }
}