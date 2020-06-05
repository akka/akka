/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.annotation.DoNotInherit

/**
 * Top level message type for signalling responses that may fail or succeed. Has special support in akka-actor-typed ask.
 *
 * Not for user extension.
 *
 * @tparam T the wrapped successful response type
 */
@DoNotInherit
sealed trait StatusResponse[+T]

object StatusResponse {
  final case class Ok[T](value: T) extends StatusResponse[T]
  final case class Fail(errorMessage: String) extends StatusResponse[Nothing]
}
