/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
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
   * Java API: The `Enqueued` singleton instance
   */
  def enqueued: QueueOfferResult = Enqueued

  /**
   * Type is used to indicate that stream is dropped an element
   */
  final case object Dropped extends QueueOfferResult

  /**
   * Java API: The `Dropped` singleton instance
   */
  def dropped: QueueOfferResult = Dropped

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
