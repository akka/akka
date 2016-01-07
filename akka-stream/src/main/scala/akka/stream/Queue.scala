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
   * - competes with `Success(true)` if element is consumed by a stream
   * - competes with `Success(false)` when stream dropped offered element
   * - competes with `StreamCompleted` when stream is completed during future is active
   * - competes with `Failure(f)` when stream is failed
   * - fails when stream is completed or you cannot call offer in this moment because of implementation rules
   * (like for backpressure mode and full buffer you need to wait for last offer call Future completion)
   *
   * @param elem element to send to a stream
   */
  def offer(elem: T): Future[StreamCallbackStatus[Boolean]]

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

sealed trait StreamCallbackStatus[+T]

/**
 * Contains types that is used as return types for async callbacks to streams
 */
object StreamCallbackStatus {

  /**
   * This class/message type is used to indicate success async call to stream.
   * @param result - result of async calls to stream
   */
  final case class Success[+T](result: T) extends StreamCallbackStatus[T]

  /**
   * Type is used to indicate that stream is failed before or during call to the stream
   * @param cause - exception that stream failed with
   */
  final case class Failure(cause: Throwable) extends StreamCallbackStatus[Nothing]

  /**
   * Type is used to indicate that stream is completed before call
   */
  case object StreamCompleted extends StreamCallbackStatus[Nothing]
}

