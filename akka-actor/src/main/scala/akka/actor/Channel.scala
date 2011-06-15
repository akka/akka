/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

/**
 * Abstraction for unification of sender and senderFuture for later reply.
 * Can be stored away and used at a later point in time.
 *
 * The possible reply channel which can be passed into ! and safe_! is always
 * untyped, as there is no way to utilize its real static type without
 * requiring runtime-costly manifests.
 */
trait Channel[-T] {

  /**
   * Scala API. <p/>
   * Sends the specified message to the channel.
   */
  def !(msg: T)(implicit channel: UntypedChannel = NullChannel): Unit

  /**
   * Try to send an exception. Not all channel types support this, one notable
   * positive example is Future. Failure to send is silent.
   */
  def sendException(ex: Throwable): Unit

  /**
   * Scala API.<p/>
   * Try to send message to the channel, return whether successful.
   */
  def safe_!(msg: T)(implicit channel: UntypedChannel = NullChannel): Boolean

  /**
   * Indicates whether this channel may be used only once, e.g. a Future.
   */
  def isUsableOnlyOnce: Boolean

  /**
   * Indicates whether this channel may still be used (only useful if
   * isUsableOnlyOnce returns true).
   */
  def isUsable: Boolean

  /**
   * Indicates whether this channel carries reply information, e.g. an
   * ActorRef.
   */
  def isReplyable: Boolean

  /**
   * Indicates whether this channel is capable of sending exceptions to its
   * recipient.
   */
  def canSendException: Boolean

  /**
   * Java API.<p/>
   * Sends the specified message to the channel, i.e. fire-and-forget semantics.<p/>
   * <pre>
   * actor.sendOneWay(message);
   * </pre>
   */
  def sendOneWay(msg: T): Unit = this.!(msg)

  /**
   * Java API. <p/>
   * Sends the specified message to the channel, i.e. fire-and-forget
   * semantics, including the sender reference if possible (not supported on
   * all channels).<p/>
   * <pre>
   * actor.sendOneWay(message, context);
   * </pre>
   */
  def sendOneWay(msg: T, sender: UntypedChannel): Unit = this.!(msg)(sender)

  /**
   * Java API.<p/>
   * Try to send the specified message to the channel, i.e. fire-and-forget semantics.<p/>
   * <pre>
   * actor.sendOneWay(message);
   * </pre>
   */
  def sendOneWaySafe(msg: T): Boolean = this.safe_!(msg)

  /**
   * Java API. <p/>
   * Try to send the specified message to the channel, i.e. fire-and-forget
   * semantics, including the sender reference if possible (not supported on
   * all channels).<p/>
   * <pre>
   * actor.sendOneWay(message, context);
   * </pre>
   */
  def sendOneWaySafe(msg: T, sender: UntypedChannel): Boolean = this.safe_!(msg)(sender)

}

/**
 * This trait represents a channel that a priori does have sending capability,
 * i.e. ! is not guaranteed to fail (e.g. NullChannel would be a
 * counter-example).
 */
trait AvailableChannel[-T] { self: Channel[T] ⇒
  def safe_!(msg: T)(implicit channel: UntypedChannel = NullChannel): Boolean = {
    if (isUsable) {
      try {
        this ! msg
        true
      } catch {
        case _ ⇒ false
      }
    } else false
  }
}

/**
 * All channels used in conjunction with MessageInvocation are untyped by
 * design, so make this explicit.
 */
trait UntypedChannel extends Channel[Any]

object UntypedChannel {
  implicit def senderOption2Channel(sender: Option[ActorRef]): UntypedChannel =
    sender match {
      case Some(actor) ⇒ actor
      case None        ⇒ NullChannel
    }
}

/**
 * Default channel when none available.
 */
case object NullChannel extends UntypedChannel {
  def !(msg: Any)(implicit channel: UntypedChannel = NullChannel) {
    throw new IllegalActorStateException("""
   No sender in scope, can't reply.
   You have probably:
      1. Sent a message to an Actor from an instance that is NOT an Actor.
      2. Invoked a method on an TypedActor from an instance NOT an TypedActor.
   You may want to have a look at safe_! for a variant returning a Boolean""")
  }
  def safe_!(msg: Any)(implicit channel: UntypedChannel = NullChannel): Boolean = false
  def sendException(ex: Throwable) {}
  def isUsableOnlyOnce = false
  def isUsable = false
  def isReplyable = false
  def canSendException = false
}

/**
 * A channel which may be forwarded: a message received with such a reply
 * channel attached can be passed on transparently such that a reply from a
 * later processing stage is sent directly back to the origin. Keep in mind
 * that not all channels can be used multiple times.
 */
trait ForwardableChannel extends UntypedChannel with AvailableChannel[Any] {
  /**
   * Get channel by which this channel would reply (ActorRef.forward takes an
   * implicit ForwardableChannel and uses its .channel as message origin)
   */
  def channel: UntypedChannel
}

object ForwardableChannel {
  implicit def someS2FC(sender: Some[ActorRef]): ForwardableChannel = sender.get
  implicit def someIS2FC(implicit sender: Some[ActorRef]): ForwardableChannel = sender.get
}

