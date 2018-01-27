/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.annotation.InternalApi

/**
 * INTERNAL API: Wrapping of messages that should be adapted by
 * adapters registered with `ActorContext.messageAdapter`.
 */
@InternalApi private[akka] final case class AdaptWithRegisteredMessageAdapter[U](msg: U)

/**
 * INTERNAL API: Wrapping of messages that should be adapted by the included
 * function. Used by `ActorContext.spawnMessageAdapter` so that the function is
 * applied in the "parent" actor (for better thread safetey)..
 */
@InternalApi private[akka] final case class AdaptMessage[U, T](msg: U, adapt: U ⇒ T) {
  def adapted: T = adapt(msg)
}

// FIXME move AskResponse in other PR
