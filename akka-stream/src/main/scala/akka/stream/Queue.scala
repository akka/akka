/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.Future

/**
 * This trait allows to have the queue as a data source for some stream.
 */
trait SourceQueue[T] {

  /**
   * Method offers next element to a stream and returns future that:
   * - completes with `Enqueued` if element is consumed by a stream
   * - completes with `Dropped` when stream dropped offered element
   * - completes with `QueueClosed` when stream is completed during future is active
   * - completes with `Failure(f)` when failure to enqueue element from upstream
   * - fails when stream is completed or you cannot call offer in this moment because of implementation rules
   * (like for backpressure mode and full buffer you need to wait for last offer call Future completion)
   *
   * @param elem element to send to a stream
   */
  def offer(elem: T): Future[QueueOfferResult]

  /**
   * Method returns future that completes when stream is completed and fails when stream failed
   */
  def watchCompletion(): Future[Unit]
}

/**
 * Trait allows to have the queue as a sink for some stream.
 * "SinkQueue" pulls data from stream with backpressure mechanism.
 */
trait SinkQueue[T] {

  /**
   * Method pulls elements from stream and returns future that:
   * - fails if stream is failed
   * - completes with None in case if stream is completed
   * - completes with `Some(element)` in case next element is available from stream.
   */
  def pull(): Future[Option[T]]
}

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

