package akka.pattern

import akka.pattern.internal.FaultyResponseMarker

/**
 * A response message that will always cause ask to fail with a [[RuntimeException]] with the given
 * error description as message.
 *
 * Can be used when a request response protocol has separate messages for a successful response and a faulty response.
 *
 * For a single response that can contain either success or failure see:
 * [[akka.actor.typed.scaladsl.MaybeFailingResponse]] and [[akka.actor.typed.javadsl.MaybeFailingResponse]].
 */
trait AlwaysFailingResponse extends FaultyResponseMarker {
  def failureDescription: String
}
