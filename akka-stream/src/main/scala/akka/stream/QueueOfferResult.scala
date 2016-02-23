/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

sealed abstract class QueueOfferResult

/**
 * Contains types that is used as return types for async callbacks to streams
 */
object QueueOfferResult {

  /**
   * Type is used to indicate that stream is successfully enqueued an element
   */
  final case object Enqueued extends QueueOfferResult

  /**
   * Type is used to indicate that stream is dropped an element
   */
  final case object Dropped extends QueueOfferResult

  /**
   * Type is used to indicate that stream is failed before or during call to the stream
   * @param cause - exception that stream failed with
   */
  final case class Failure(cause: Throwable) extends QueueOfferResult

  /**
   * Type is used to indicate that stream is completed before call
   */
  case object QueueClosed extends QueueOfferResult
}
