/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.dispatch.{ Envelope, DequeBasedMessageQueue }

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
 *  the following:
 *  <pre>
 *  akka {
 *    actor {
 *      default-dispatcher {
 *        mailboxType = "akka.dispatch.UnboundedDequeBasedMailbox"
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
  private[this] var theStash = Vector.empty[Envelope]

  /* The actor's deque-based message queue.
   * `mailbox.queue` is the underlying `Deque`.
   */
  private[this] val mailbox: DequeBasedMessageQueue = {
    context.asInstanceOf[ActorCell].mailbox match {
      case queue: DequeBasedMessageQueue ⇒ queue
      case other                         ⇒ throw new ActorInitializationException(self, "UnboundedDequeBasedMailbox required, got: " + other.getClass())
    }
  }

  /**
   *  Adds the current message (the message that the actor received last) to the
   *  actor's stash.
   */
  def stash(): Unit = theStash :+= context.asInstanceOf[ActorCell].currentMessage

  /**
   *  Prepends all messages in the stash to the mailbox, and then clears the stash.
   */
  def unstashAll(): Unit = {
    theStash.reverseIterator foreach mailbox.queue.addFirst
    theStash = Vector.empty[Envelope]
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
