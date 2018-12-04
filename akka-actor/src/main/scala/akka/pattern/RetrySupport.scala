/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor.Scheduler
import akka.annotation.DoNotInherit

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

/**
 * This trait provides the retry utility function.
 *
 * Not intended for user extension.
 */
@DoNotInherit
trait RetrySupport {

  /**
   * Given a function from Unit to Future, returns an internally retrying Future.
   * The first attempt will be made immediately, each subsequent attempt will be made after 'delay'.
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry.
   * If attempts are exhausted the returned future is simply the result of invoking attempt.
   * Note that the attempt function will be invoked on the given execution context for subsequent
   * tries and therefore must be thread safe (not touch unsafe mutable state).
   *
   * <b>Example usage:</b>
   *
   * {{{
   * protected val sendAndReceive: HttpRequest => Future[HttpResponse]
   * private val sendReceiveRetry: HttpRequest => Future[HttpResponse] = (req: HttpRequest) => retry[HttpResponse](
   *   attempt = () => sendAndReceive(req),
   *   attempts = 10,
   *   delay = 2 seconds,
   *   scheduler = context.system.scheduler
   * )
   * }}}
   */
  def retry[T](attempt: () ⇒ Future[T], attempts: Int, delay: FiniteDuration)(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    retry(attempt, attempts, delay, (_, initialDelay, _) ⇒ initialDelay)
  }

  /**
   * Given a function from Unit to Future, returns an internally retrying Future.
   * The first attempt will be made immediately, each subsequent attempt will be made after 'delay'.
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry.
   * You could provide a function to generate the next delay duration after first attempt,
   * this function should never return `null`, otherwise an [[IllegalArgumentException]] will be through.
   * If attempts are exhausted the returned future is simply the result of invoking attempt.
   * Note that the attempt function will be invoked on the given execution context for subsequent
   * tries and therefore must be thread safe (not touch unsafe mutable state).
   *
   * <b>Example usage:</b>
   *
   * //retry with back off
   * {{{
   * protected val sendAndReceive: HttpRequest => Future[HttpResponse]
   * private val sendReceiveRetry: HttpRequest => Future[HttpResponse] = (req: HttpRequest) => retry[HttpResponse](
   *   attempt = () => sendAndReceive(req),
   *   attempts = 10,
   *   initialDelay = 2 seconds,
   *   nextDelayFunction = (attempted, initialDelay, currentDelay) => initialDelay * attempted
   *   scheduler = context.system.scheduler
   * )
   * }}}
   */
  def retry[T](
    attempt:           () ⇒ Future[T],
    attempts:          Int,
    initialDelay:      FiniteDuration,
    nextDelayFunction: (Int, FiniteDuration, FiniteDuration) ⇒ FiniteDuration)(
    implicit
    ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    RetrySupport.retry(
      attempts, attempt, attempted = 0, initialDelay, initialDelay, nextDelayFunction, isFirst = true)
  }

}

object RetrySupport extends RetrySupport {

  private def retry[T](
    maxAttempts:       Int,
    attempt:           () ⇒ Future[T],
    attempted:         Int,
    initialDelay:      FiniteDuration,
    currentDelay:      FiniteDuration,
    nextDelayFunction: (Int, FiniteDuration, FiniteDuration) ⇒ FiniteDuration,
    isFirst:           Boolean)(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    try {
      if (maxAttempts - attempted > 0) {
        attempt() recoverWith {
          case NonFatal(_) ⇒
            val nextDelay = if (isFirst) currentDelay else {
              val delay = nextDelayFunction(attempted, initialDelay, currentDelay)
              require(
                delay ne null,
                s"nextDelayFunction should never return null for arguments:[$attempted,$initialDelay,$currentDelay]")
              delay
            }
            after(nextDelay, scheduler) {
              retry(
                maxAttempts,
                attempt,
                attempted + 1, initialDelay, nextDelay, nextDelayFunction, isFirst = false)
            }
        }
      } else {
        attempt()
      }
    } catch {
      case NonFatal(error) ⇒ Future.failed(error)
    }
  }
}
