/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.dispatch.{ Envelope, DequeBasedMessageQueue }
import akka.AkkaException

/**
 *  The `Stash` trait enables an actor to temporarily stash away messages that can not or
 *  should not be handled using the actor's current behavior.
 *  <p/>
 *  Example:
 *  <pre>
 *    class ActorWithProtocol extends Actor with Stash {
 *      def receive = {
 *        case "open" ⇒
 *          unstashAll {
 *            case "write" ⇒ // do writing...
 *            case "close" ⇒
 *              unstashAll()
 *              context.unbecome()
 *            case msg ⇒ stash()
 *          }
 *        case "done" ⇒ // done
 *        case msg    ⇒ stash()
 *      }
 *    }
 *  </pre>
 *
 *  Note that the `Stash` trait can only be used together with actors that have a deque-based
 *  mailbox. Actors can be configured to use a deque-based mailbox using a configuration like
 *  the following (see the documentation on dispatchers on how to configure a custom
 *  dispatcher):
 *  <pre>
 *  akka {
 *    actor {
 *      my-custom-dispatcher {
 *        mailbox-type = "akka.dispatch.UnboundedDequeBasedMailbox"
 *      }
 *    }
 *  }
 *  </pre>
 */
trait Stash extends Actor {
  this: Actor ⇒

  /* The private stash of the actor. It is only accessible using `stash()` and
   * `unstashAll()`.
   */
  private var theStash = Vector.empty[Envelope]

  /* The capacity of the stash. Configured in the actor's dispatcher config.
   */
  private val capacity = {
    val dispatcher = context.system.settings.config.getConfig(context.props.dispatcher)
    val config = dispatcher.withFallback(context.system.settings.config.getConfig("akka.actor.default-dispatcher"))
    config.getInt("stash-capacity")
  }

  /* The actor's deque-based message queue.
   * `mailbox.queue` is the underlying `Deque`.
   */
  private val mailbox: DequeBasedMessageQueue = {
    context.asInstanceOf[ActorCell].mailbox.messageQueue match {
      case queue: DequeBasedMessageQueue ⇒ queue
      case other ⇒ throw new ActorInitializationException(self, "DequeBasedMailbox required, got: " + other.getClass() + """
An (unbounded) deque-based mailbox can be configured as follows:
  my-custom-dispatcher {
    mailbox-type = "akka.dispatch.UnboundedDequeBasedMailbox"
  }
""")
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
    if (theStash.size > 0 && (currMsg eq theStash.last))
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
   *
   *  @throws MessageQueueAppendFailedException in case of a capacity violation when
   *          prepending the stash to a bounded mailbox
   */
  def unstashAll(): Unit = {
    try {
      for (msg ← theStash.reverseIterator) mailbox.enqueueFirst(self, msg)
    } finally {
      theStash = Vector.empty[Envelope]
    }
  }

  /**
   *  Prepends all messages in the stash to the mailbox, clears the stash, and then
   *  assumes the argument behavior. The invocation `unstashAll(handler)` is
   *  equivalent to the following code:
   *  <pre>
   *    unstashAll()
   *    context.become(handler)
   *  </pre>
   *
   *  @param handler the behavior that should be assumed after unstashing
   *  all messages.
   */
  def unstashAll(handler: Receive): Unit = {
    unstashAll()
    context.become(handler)
  }

  /**
   *  Overridden callback. Prepends all messages in the stash to the mailbox,
   *  clears the stash, and invokes the callback of the superclass.
   */
  override def preRestart(reason: Throwable, message: Option[Any]) {
    unstashAll()
    super.preRestart(reason, message)
  }

}

class StashOverflowException(message: String, cause: Throwable = null) extends AkkaException(message, cause)
