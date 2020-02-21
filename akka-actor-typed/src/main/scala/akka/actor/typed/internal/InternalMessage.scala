/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.actor.WrappedMessage
import akka.actor.typed.Signal
import akka.annotation.InternalApi

/**
 * A marker trait for internal messages.
 */
@InternalApi private[akka] sealed trait InternalMessage

/**
 * INTERNAL API: Wrapping of messages that should be adapted by
 * adapters registered with `ActorContext.messageAdapter`.
 */
@InternalApi private[akka] final case class AdaptWithRegisteredMessageAdapter[U](msg: U) extends InternalMessage

/**
 * INTERNAL API: Wrapping of messages that should be adapted by the included
 * function. Used by `ActorContext.spawnMessageAdapter` and `ActorContext.ask` so that the function is
 * applied in the "parent" actor (for better thread safety)..
 */
@InternalApi private[akka] final case class AdaptMessage[U, T](message: U, adapter: U => T)
    extends InternalMessage
    with WrappedMessage {
  def adapt(): T = adapter(message)
}

/**
 * INTERNAL API: Wrapped exception to pass a failure to adapt a message into the behavior stack and
 * let supervision apply also to such failures.
 */
@InternalApi
private[akka] final case class MessageAdaptionFailure(ex: Throwable) extends Signal
