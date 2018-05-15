/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.Optional
import java.util.concurrent.CompletionStage

import scala.concurrent.Future
import akka.Done
import akka.stream.QueueOfferResult

import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._

/**
 * This trait allows to have a queue as a data source for some stream.
 */
trait SourceQueue[T] {

  /**
   * Offers an element to a stream and returns a [[Future]] that:
   * - completes with `Enqueued` if the element is consumed by a stream
   * - completes with `Dropped` when the stream dropped the offered element
   * - completes with `QueueClosed` when the stream is completed whilst the [[Future]] is active
   * - completes with `Failure(f)` in case of failure to enqueue element from upstream
   * - fails when stream is already completed
   *
   * Additionally when using the backpressure overflowStrategy:
   * - If the buffer is full the [[Future]] won't be completed until there is space in the buffer
   * - Calling offer before the [[Future]] is completed, in this case it will return a failed [[Future]]
   *
   * @param elem element to send to a stream
   */
  def offer(elem: T): Future[QueueOfferResult]

  /**
   * Returns a [[Future]] that will be
   * - completed if the stream completes
   * - failed when the operator faces an internal failure
   */
  def watchCompletion(): Future[Done]
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
   * Method returns a [[Future]] that will be
   * - completed if the stream completes
   * - failed when
   *   - the operator faces an internal failure
   *   - the [[SourceQueueWithComplete.fail]] method is invoked.
   */
  def watchCompletion(): Future[Done]
}

object SourceQueueWithComplete {
  implicit def QueueOps[T](f: SourceQueueWithComplete[T]): QueueOps[T] = new QueueOps[T](f)
  final class QueueOps[T](val queue: SourceQueueWithComplete[T]) extends AnyVal {
    def asJava: akka.stream.javadsl.SourceQueueWithComplete[T] = SourceQueueConverter.asJava(queue)
  }
}

object SourceQueueConverter {
  /**
   * Converts the queue into a `javadsl.SourceQueueWithComplete`
   */
  def asJava[T](queue: SourceQueueWithComplete[T]): akka.stream.javadsl.SourceQueueWithComplete[T] =
    new akka.stream.javadsl.SourceQueueWithComplete[T] {
      def offer(elem: T): CompletionStage[QueueOfferResult] = queue.offer(elem).toJava
      def watchCompletion(): CompletionStage[Done] = queue.watchCompletion().toJava
      def complete(): Unit = queue.complete()
      def fail(ex: Throwable): Unit = queue.fail(ex)
    }
}

/**
 * This trait allows to have a queue as a sink for a stream.
 * A [[SinkQueue]] pulls data from a stream with a backpressure mechanism.
 */
trait SinkQueue[T] {

  /**
   * Pulls elements from the stream and returns a [[Future]] that:
   * - fails if the stream is failed
   * - completes with None in case the stream is completed
   * - completes with `Some(element)` in case the next element is available from stream.
   */
  def pull(): Future[Option[T]]
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
    def asJava: akka.stream.javadsl.SinkQueueWithCancel[T] = SinkQueueConverter.asJava(queue)
  }
}

object SinkQueueConverter {
  /**
   * Converts the queue into a `javadsl.SinkQueueWithCancel`
   */
  def asJava[T](queue: SinkQueueWithCancel[T]): akka.stream.javadsl.SinkQueueWithCancel[T] =
    new akka.stream.javadsl.SinkQueueWithCancel[T] {
      import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ same }
      override def pull(): CompletionStage[Optional[T]] = queue.pull().map(_.asJava)(same).toJava
      override def cancel(): Unit = queue.cancel()
    }
}
