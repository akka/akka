/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.annotation.DoNotInherit

import scala.concurrent.Future

/**
 * A queue of the given size that gives immediate feedback whether an element could be enqueued or not.
 *
 * Not for user extension
 */
@DoNotInherit
trait BoundedSourceQueue[T] {

  /**
   * Returns a [[akka.stream.QueueOfferResult]] that notifies the caller if the element could be enqueued or not, or
   * the completion status of the queue.
   *
   * A result of `QueueOfferResult.Enqueued` does not guarantee that an element also has been or will be processed by
   * the downstream.
   */
  def offer(elem: T): QueueOfferResult

  /**
   * If [[BoundedSourceQueue#offer]] returns [[akka.stream.QueueOfferResult.Dropped]] then a caller can use this method
   * to be notified when the queue has available capacity to accept a new element. In case of multiple producers, all
   * will be notified. Therefore, it is not guaranteed that upon [[Future]] completion the next offer will be accepted.
   * First (or as much as there is capacity) offer wins and others have to try again.
   */
  def whenReady(): Future[akka.Done]

  /**
   * Completes the stream normally.
   */
  def complete(): Unit

  /**
   * Completes the stream with a failure.
   */
  def fail(ex: Throwable): Unit
}
