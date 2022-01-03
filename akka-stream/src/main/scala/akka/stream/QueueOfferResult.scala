/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.annotation.DoNotInherit

/**
 * Not for user extension
 */
@DoNotInherit
sealed abstract class QueueOfferResult

/**
 * Not for user extension
 */
@DoNotInherit
sealed abstract class QueueCompletionResult extends QueueOfferResult

/**
 * Contains types that is used as return types for streams Source queues
 */
object QueueOfferResult {

  /**
   * Type is used to indicate that stream is successfully enqueued an element
   */
  case object Enqueued extends QueueOfferResult

  /**
   * Java API: The `Enqueued` singleton instance
   */
  def enqueued: QueueOfferResult = Enqueued

  /**
   * Type is used to indicate that stream is dropped an element
   */
  case object Dropped extends QueueOfferResult

  /**
   * Java API: The `Dropped` singleton instance
   */
  def dropped: QueueOfferResult = Dropped

  /**
   * Type is used to indicate that stream is failed before or during call to the stream
   * @param cause - exception that stream failed with
   */
  final case class Failure(cause: Throwable) extends QueueCompletionResult

  /**
   * Type is used to indicate that stream is completed before call
   */
  case object QueueClosed extends QueueCompletionResult
}
