/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.concurrent.Future
import akka.Done
import akka.annotation.DoNotInherit
import akka.stream.QueueOfferResult
import akka.stream.stage.GraphStageLogic

import scala.util.control.NoStackTrace

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
   * Method returns a [[Future]] that will be completed if the stream completes,
   * or will be failed when the stage faces an internal failure.
   */
  def watchCompletion(): Future[Done]
}

/**
 * This trait adds completion support to [[SourceQueue]].
 */
trait SourceQueueWithComplete[T] extends SourceQueue[T] {
  /**
   * Complete the stream normally. Use `watchCompletion` to be notified of this
   * operation’s success.
   */
  def complete(): Unit

  /**
   * Complete the stream with a failure. Use `watchCompletion` to be notified of this
   * operation’s success.
   */
  def fail(ex: Throwable): Unit

  /**
   * Method returns a [[Future]] that will be completed if the stream completes,
   * or will be failed when the stage faces an internal failure or the the [[SourceQueueWithComplete.fail]] method is invoked.
   */
  def watchCompletion(): Future[Done]
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

/**
 * This trait adds cancel support to [[SinkQueue]].
 */
trait SinkQueueWithCancel[T] extends SinkQueue[T] {
  /**
   * Cancel the stream. This method returns right away without waiting for actual finalizing stream.
   */
  def cancel(): Unit
}
