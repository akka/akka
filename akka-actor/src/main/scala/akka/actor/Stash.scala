/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.dispatch.{ UnboundedDequeBasedMessageQueueSemantics, RequiresMessageQueue, Envelope, DequeBasedMessageQueueSemantics }
import akka.AkkaException
import akka.dispatch.Mailboxes

/**
 *  The `Stash` trait enables an actor to temporarily stash away messages that can not or
 *  should not be handled using the actor's current behavior.
 *  <p/>
 *  Example:
 *  <pre>
 *    class ActorWithProtocol extends Actor with Stash {
 *      def receive = {
 *        case "open" ⇒
 *          unstashAll()
 *          context.become({
 *            case "write" ⇒ // do writing...
 *            case "close" ⇒
 *              unstashAll()
 *              context.unbecome()
 *            case msg ⇒ stash()
 *          }, discardOld = false)
 *        case "done" ⇒ // done
 *        case msg    ⇒ stash()
 *      }
 *    }
 *  </pre>
 *
 *  Note that the `Stash` trait can only be used together with actors that have a deque-based
 *  mailbox. By default Stash based actors request a Deque based mailbox since the stash
 *  trait extends `RequiresMessageQueue[DequeBasedMessageQueueSemantics]`.
 *  You can override the default mailbox provided when `DequeBasedMessageQueueSemantics` are requested via config:
 *  <pre>
 *    akka.actor.mailbox.requirements {
 *      "akka.dispatch.BoundedDequeBasedMessageQueueSemantics" = your-custom-mailbox
 *    }
 *  </pre>
 *  Alternatively, you can add your own requirement marker to the actor and configure a mailbox type to be used
 *  for your marker.
 *
 *  For a `Stash` that also enforces unboundedness of the deque see [[akka.actor.UnboundedStash]]. For a `Stash`
 *  that does not enforce any mailbox type see [[akka.actor.UnrestrictedStash]].
 *
 *  Note that the `Stash` trait must be mixed into (a subclass of) the `Actor` trait before
 *  any trait/class that overrides the `preRestart` callback. This means it's not possible to write
 *  `Actor with MyActor with Stash` if `MyActor` overrides `preRestart`.
 */
trait Stash extends UnrestrictedStash with RequiresMessageQueue[DequeBasedMessageQueueSemantics]

/**
 * The `UnboundedStash` trait is a version of [[akka.actor.Stash]] that enforces an unbounded stash for you actor.
 */
trait UnboundedStash extends UnrestrictedStash with RequiresMessageQueue[UnboundedDequeBasedMessageQueueSemantics]

/**
 * A version of [[akka.actor.Stash]] that does not enforce any mailbox type. The proper mailbox has to be configured
 * manually, and the mailbox should extend the [[akka.dispatch.DequeBasedMessageQueueSemantics]] marker trait.
 */
trait UnrestrictedStash extends Actor {
  /* The private stash of the actor. It is only accessible using `stash()` and
   * `unstashAll()`.
   */
  private var theStash = Vector.empty[Envelope]

  /* The capacity of the stash. Configured in the actor's mailbox or dispatcher config.
   */
  private val capacity: Int = {
    val dispatcher = context.system.settings.config.getConfig(context.props.dispatcher)
    val fallback = dispatcher.withFallback(context.system.settings.config.getConfig(Mailboxes.DefaultMailboxId))
    val config =
      if (context.props.mailbox == Mailboxes.DefaultMailboxId) fallback
      else context.system.settings.config.getConfig(context.props.mailbox).withFallback(fallback)
    config.getInt("stash-capacity")
  }

  /* The actor's deque-based message queue.
   * `mailbox.queue` is the underlying `Deque`.
   */
  private val mailbox: DequeBasedMessageQueueSemantics = {
    context.asInstanceOf[ActorCell].mailbox.messageQueue match {
      case queue: DequeBasedMessageQueueSemantics ⇒ queue
      case other ⇒ throw ActorInitializationException(self, s"DequeBasedMailbox required, got: ${other.getClass.getName}\n" +
        """An (unbounded) deque-based mailbox can be configured as follows:
          |  my-custom-mailbox {
          |    mailbox-type = "akka.dispatch.UnboundedDequeBasedMailbox"
          |  }
          |""".stripMargin)
    }
  }

  /**
   *  Adds the current message (the message that the actor received last) to the
   *  actor's stash.
   *
   *  @throws StashOverflowException in case of a stash capacity violation
   *  @throws IllegalStateException  if the same message is stashed more than once
   */
  def stash(): Unit = {
    val currMsg = context.asInstanceOf[ActorCell].currentMessage
    if (theStash.nonEmpty && (currMsg eq theStash.last))
      throw new IllegalStateException("Can't stash the same message " + currMsg + " more than once")
    if (capacity <= 0 || theStash.size < capacity) theStash :+= currMsg
    else throw new StashOverflowException("Couldn't enqueue message " + currMsg + " to stash of " + self)
  }

  /**
   *  Prepends all messages in the stash to the mailbox, and then clears the stash.
   *
   *  Messages from the stash are enqueued to the mailbox until the capacity of the
   *  mailbox (if any) has been reached. In case a bounded mailbox overflows, a
   *  `MessageQueueAppendFailedException` is thrown.
   *
   *  The stash is guaranteed to be empty after calling `unstashAll()`.
   */
  def unstashAll(): Unit = {
    try {
      val i = theStash.reverseIterator
      while (i.hasNext) mailbox.enqueueFirst(self, i.next())
    } finally {
      theStash = Vector.empty[Envelope]
    }
  }

  /**
   *  Overridden callback. Prepends all messages in the stash to the mailbox,
   *  clears the stash, stops all children and invokes the postStop() callback.
   */
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    try unstashAll() finally super.preRestart(reason, message)
  }

  /**
   *  Overridden callback. Prepends all messages in the stash to the mailbox and clears the stash.
   *  Must be called when overriding this method, otherwise stashed messages won't be propagated to DeadLetters
   *  when actor stops.
   */
  override def postStop(): Unit = try unstashAll() finally super.postStop()

}

/**
 * Is thrown when the size of the Stash exceeds the capacity of the Stash
 */
class StashOverflowException(message: String, cause: Throwable = null) extends AkkaException(message, cause)
