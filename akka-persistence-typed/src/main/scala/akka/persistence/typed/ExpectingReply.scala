/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.actor.typed.ActorRef

/**
 * Commands may implement this trait to facilitate sending reply messages via `Effect.thenReply`.
 *
 * @tparam ReplyMessage The type of the reply message
 */
trait ExpectingReply[ReplyMessage] {
  def replyTo: ActorRef[ReplyMessage]
}
