/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.control.NonFatal

import akka.actor.Scheduler
import akka.util.ConstantFun

/**
 * This trait provides the retry utility function
 */
trait RetrySupport {

  /**
   * Given a function from Unit to Future, returns an internally retrying Future.
   * The first attempt will be made immediately, each subsequent attempt will be made immediately
   * if the previous attempt failed.
   *
   * If attempts are exhausted the returned future is simply the result of invoking attempt.
   * Note that the attempt function will be invoked on the given execution context for subsequent
   * tries and therefore must be thread safe (not touch unsafe mutable state).
   *
   * <b>Example usage:</b>
   *
   * {{{
   * def possiblyFailing(): Future[Something] = ???
   * val withRetry: Future[Something] = retry(attempt = possiblyFailing, attempts = 10)
   * }}}
   */
  def retry[T](attempt: () => Future[T], attempts: Int)(implicit ec: ExecutionContext): Future[T] = {
    RetrySupport.retry(attempt, attempts, attempted = 0)
  }

  /**
   * Given a function from Unit to Future, returns an internally retrying Future.
   * The first attempt will be made immediately, each subsequent attempt will be made with a backoff time,
   * if the previous attempt failed.
   *
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
   *   minBackoff = 1.seconds,
   *   maxBackoff = 2.seconds,
   *   randomFactor = 0.5
   * )
   * }}}
   *
   * @param minBackoff   minimum (initial) duration until the child actor will
   *                     started again, if it is terminated
   * @param maxBackoff   the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *                     random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *                     In order to skip this additional delay pass in `0`.
   */
  def retry[T](
      attempt: () => Future[T],
      attempts: Int,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double)(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    require(attempt != null, "Parameter attempt should not be null.")
    require(minBackoff != null, "Parameter minBackoff should not be null.")
    require(maxBackoff != null, "Parameter maxBackoff should not be null.")
    require(minBackoff > Duration.Zero, "Parameter minBackoff must be > 0")
    require(maxBackoff >= minBackoff, "Parameter maxBackoff must be >= minBackoff")
    require(0.0 <= randomFactor && randomFactor <= 1.0, "randomFactor must be between 0.0 and 1.0")
    retry(
      attempt,
      attempts,
      attempted => Some(BackoffSupervisor.calculateDelay(attempted, minBackoff, maxBackoff, randomFactor)))
  }

  /**
   * Given a function from Unit to Future, returns an internally retrying Future.
   * The first attempt will be made immediately, each subsequent attempt will be made after 'delay'.
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry.
   *
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
   *   delay = 2.seconds
   * )
   * }}}
   */
  def retry[T](attempt: () => Future[T], attempts: Int, delay: FiniteDuration)(
      implicit ec: ExecutionContext,
      scheduler: Scheduler): Future[T] = {
    retry(attempt, attempts, _ => Some(delay))
  }

  /**
   * Given a function from Unit to Future, returns an internally retrying Future.
   * The first attempt will be made immediately, each subsequent attempt will be made after
   * the 'delay' return by `delayFunction`(the input next attempt count start from 1).
   * Returns [[None]] for no delay.
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry.
   * You could provide a function to generate the next delay duration after first attempt,
   * this function should never return `null`, otherwise an [[IllegalArgumentException]] will be through.
   *
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
   *   delayFunction = attempted => Option(2.seconds * attempted)
   * )
   * }}}
   */
  def retry[T](attempt: () => Future[T], attempts: Int, delayFunction: Int => Option[FiniteDuration])(
      implicit
      ec: ExecutionContext,
      scheduler: Scheduler): Future[T] = {
    RetrySupport.retry(attempt, attempts, delayFunction, attempted = 0)
  }
}

object RetrySupport extends RetrySupport {

  private def retry[T](attempt: () => Future[T], maxAttempts: Int, attempted: Int)(
      implicit ec: ExecutionContext): Future[T] =
    retry(attempt, maxAttempts, ConstantFun.scalaAnyToNone, attempted)(ec, null)

  private def retry[T](
      attempt: () => Future[T],
      maxAttempts: Int,
      delayFunction: Int => Option[FiniteDuration],
      attempted: Int)(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {

    def tryAttempt(): Future[T] = {
      try {
        attempt()
      } catch {
        case NonFatal(exc) => Future.failed(exc) // in case the `attempt` function throws
      }
    }

    require(maxAttempts >= 0, "Parameter maxAttempts must >= 0.")
    require(attempt != null, "Parameter attempt should not be null.")
    if (maxAttempts - attempted > 0) {
      val result = tryAttempt()
      if (result eq null)
        result
      else {
        val nextAttempt = attempted + 1
        result.recoverWith {
          case NonFatal(_) =>
            delayFunction(nextAttempt) match {
              case Some(delay) =>
                if (delay.length < 1)
                  retry(attempt, maxAttempts, delayFunction, nextAttempt)
                else
                  after(delay, scheduler) {
                    retry(attempt, maxAttempts, delayFunction, nextAttempt)
                  }
              case None =>
                retry(attempt, maxAttempts, delayFunction, nextAttempt)
              case _ =>
                Future.failed(new IllegalArgumentException("The delayFunction of retry should not return null."))
            }

        }
      }

    } else {
      tryAttempt()
    }
  }
}
