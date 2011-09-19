/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

/*
 * This package is just used to hide the tryTell method from the Scala parts:
 * it will be public to javac's eyes by virtue of §5.2 of the SLS.
 */
package japi {
  trait Channel[-T] { self: akka.actor.Channel[T] ⇒
    private[japi] def tryTell(msg: T): Boolean = {
      try {
        self.!(msg)(NullChannel)
        true
      } catch {
        case _: Exception ⇒ false
      }
    }
  }
}

/**
 * Abstraction for unification of sender and senderFuture for later reply.
 * Can be stored away and used at a later point in time.
 *
 * The possible reply channel which can be passed into ! and tryTell is always
 * untyped, as there is no way to utilize its real static type without
 * requiring runtime-costly manifests.
 */
trait Channel[-T] extends japi.Channel[T] {

  /**
   * Scala API. <p/>
   * Sends the specified message to the channel.
   */
  def !(msg: T)(implicit sender: UntypedChannel): Unit

  /**
   * Scala and Java API. <p/>
   * Try to send the specified message to the channel, i.e. fire-and-forget
   * semantics, including the sender reference if possible (not supported on
   * all channels).<p/>
   * From Java:
   * <pre>
   * actor.tryTell(message);
   * actor.tryTell(message, context);
   * </pre>
   * <p/>
   * From Scala:
   * <pre>
   * actor tryTell message
   * actor.tryTell(message)(sender)
   * </pre>
   */
  def tryTell(msg: T)(implicit sender: UntypedChannel): Boolean = {
    try {
      this.!(msg)(sender)
      true
    } catch {
      case _: Exception ⇒ false
    }
  }

  /**
   * Try to send an exception. Not all channel types support this, one notable
   * positive example is Future. Failure to send is silent.
   *
   * @return whether sending was successful
   */
  def sendException(ex: Throwable): Boolean = false

  /**
   * Sends the specified message to the channel, i.e. fire-and-forget semantics.<p/>
   * <pre>
   * actor.tell(message);
   * </pre>
   */
  def tell(msg: T): Unit = this.!(msg)

  /**
   * Java API. <p/>
   * Sends the specified message to the channel, i.e. fire-and-forget
   * semantics, including the sender reference if possible (not supported on
   * all channels).<p/>
   * <pre>
   * actor.tell(message, context);
   * </pre>
   */
  def tell(msg: T, sender: UntypedChannel): Unit = this.!(msg)(sender)

}

/**
 * This trait marks a channel that a priori does have sending capability,
 * i.e. ! is not guaranteed to fail (e.g. NullChannel would be a
 * counter-example).
 */
trait AvailableChannel[-T] extends Channel[T]

/**
 * This trait marks a channel which is capable of sending exceptions.
 */
trait ExceptionChannel[-T] extends AvailableChannel[T]

/**
 * This trait marks a channel which carries reply information when tell()ing.
 */
trait ReplyChannel[-T] extends AvailableChannel[T]

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

  implicit final val default: UntypedChannel = NullChannel
}

/**
 * Default channel when none available.
 */
case object NullChannel extends UntypedChannel {
  def !(msg: Any)(implicit channel: UntypedChannel) {
    throw new IllegalActorStateException("""
   No sender in scope, can't reply.
   You have probably:
      1. Sent a message to an Actor from an instance that is NOT an Actor.
      2. Invoked a method on an TypedActor from an instance NOT an TypedActor.
   You may want to have a look at tryTell for a variant returning a Boolean""")
  }
  override def tryTell(msg: Any)(implicit channel: UntypedChannel): Boolean = false
}

/**
 * Wraps a forwardable channel. Used implicitly by ScalaActorRef.forward
 */
case class ForwardableChannel(val channel: UntypedChannel)
