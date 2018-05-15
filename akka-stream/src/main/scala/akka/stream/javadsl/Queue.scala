/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import java.util.Optional
import java.util.concurrent.CompletionStage

import akka.Done
import akka.stream.QueueOfferResult

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.Future

/**
 * This trait allows to have a queue as a data source for some stream.
 */
trait SourceQueue[T] {

  /**
   * Offers an element to a stream and returns a [[CompletionStage]] that:
   * - completes with `Enqueued` if the element is consumed by a stream
   * - completes with `Dropped` when the stream dropped the offered element
   * - completes with `QueueClosed` when the stream is completed whilst the [[CompletionStage]] is active
   * - completes with `Failure(f)` in case of failure to enqueue element from upstream
   * - fails when stream is already completed
   *
   * Additionally when using the backpressure overflowStrategy:
   * - If the buffer is full the [[CompletionStage]] won't be completed until there is space in the buffer
   * - Calling offer before the [[CompletionStage]] is completed, in this case it will return a failed [[CompletionStage]]
   *
   * @param elem element to send to a stream
   */
  def offer(elem: T): CompletionStage[QueueOfferResult]

  /**
   * Returns a [[CompletionStage]] that will be
   * - completed if the stream completes
   * - failed when the operator faces an internal failure
   */
  def watchCompletion(): CompletionStage[Done]
}

/**
 * This trait adds completion support to [[SourceQueue]].
 */
trait SourceQueueWithComplete[T] extends SourceQueue[T] {

  /**
   * Completes the stream normally. Use `watchCompletion` to be notified of this
   * operation’s success.
   */
  def complete(): Unit

  /**
   * Completes the stream with a failure. Use `watchCompletion` to be notified of this
   * operation’s success.
   */
  def fail(ex: Throwable): Unit

  /**
   * Method returns a [[CompletionStage]] that will be
   * - completed if the stream completes
   * - failed when
   *   - the operator faces an internal failure
   *   - the [[SourceQueueWithComplete.fail]] method is invoked.
   */
  def watchCompletion(): CompletionStage[Done]
}

object SourceQueueWithComplete {
  implicit def QueueOps[T](f: SourceQueueWithComplete[T]): QueueOps[T] = new QueueOps[T](f)
  final class QueueOps[T](val queue: SourceQueueWithComplete[T]) extends AnyVal {
    def asScala: akka.stream.scaladsl.SourceQueueWithComplete[T] = SourceQueueConverter.asScala(queue)
  }
}

object SourceQueueConverter {
  /**
   * Converts the queue into a `scaladsl.SourceQueueWithComplete`
   */
  def asScala[T](queue: SourceQueueWithComplete[T]): akka.stream.scaladsl.SourceQueueWithComplete[T] =
    new akka.stream.scaladsl.SourceQueueWithComplete[T] {
      def offer(elem: T): Future[QueueOfferResult] = queue.offer(elem).toScala
      def watchCompletion(): Future[Done] = queue.watchCompletion().toScala
      def complete(): Unit = queue.complete()
      def fail(ex: Throwable): Unit = queue.fail(ex)
    }
}

/**
 * This trait allows to have a queue as a sink for a stream.
 * A [[SinkQueue]] pulls data from stream with a backpressure mechanism.
 */
trait SinkQueue[T] {

  /**
   * Pulls elements from the stream and returns a [[CompletionStage]] that:
   * - fails if the stream is failed
   * - completes with Empty in case the stream is completed
   * - completes with `element` in case the next element is available from the stream.
   */
  def pull(): CompletionStage[Optional[T]]
}

/**
 * This trait adds cancel support to [[SinkQueue]].
 */
trait SinkQueueWithCancel[T] extends SinkQueue[T] {
  /**
   * Cancels the stream. This method returns right away without waiting for actual finalizing the stream.
   */
  def cancel(): Unit
}

object SinkQueueWithCancel {
  implicit def QueueOps[T](f: SinkQueueWithCancel[T]): QueueOps[T] = new QueueOps[T](f)
  final class QueueOps[T](val queue: SinkQueueWithCancel[T]) extends AnyVal {
    def asScala: akka.stream.scaladsl.SinkQueueWithCancel[T] = SinkQueueConverter.asScala(queue)
  }
}

object SinkQueueConverter {
  /**
   * Converts the queue into a `scaladsl.SinkQueueWithCancel`
   */
  def asScala[T](queue: SinkQueueWithCancel[T]): akka.stream.scaladsl.SinkQueueWithCancel[T] =
    new akka.stream.scaladsl.SinkQueueWithCancel[T] {
      import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ same }
      override def pull(): Future[Option[T]] = queue.pull().toScala.map(_.asScala)(same)
      override def cancel(): Unit = queue.cancel()
    }
}
