/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl

import akka.Done
import java.util.concurrent.CompletionStage
import java.util.Optional
import akka.stream.QueueOfferResult

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
  def offer(elem: T): CompletionStage[QueueOfferResult]

  /**
   * Method returns future that completes when stream is completed and fails when stream failed
   */
  def watchCompletion(): CompletionStage[Done]
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
  def pull(): CompletionStage[Optional[T]]
}
