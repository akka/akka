/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor.Scheduler

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

/**
 * This trait provides the retry utility function
 */
trait RetrySupport {

  /**
   * Given a function from Unit to Future, returns an internally retrying Future
   * The first attempt will be made immediately, each subsequent attempt will be made after 'delay'
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry
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
   *   delay = 2 seconds)(
   *    ec = context.system
   *    scheduler = context.system.scheduler
   * )
   * }}}
   */
  def retry[T](attempt: () => Future[T], attempts: Int, delay: FiniteDuration)(
      implicit ec: ExecutionContext,
      scheduler: Scheduler): Future[T] =
    retryF(attempt, attempts, delay, 1)((_, _) => ())

  /**
   * Given a function from Unit to Future, returns an internally retrying Future
   * The first attempt will be made immediately, each subsequent attempt will be made after 'delay' * 'backoff'
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry
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
   *   backoff = 2)(
   *    ec = context.system
   *    scheduler = context.system.scheduler
   * )
   * }}}
   */
  def retry[T](attempt: () => Future[T], attempts: Int, delay: FiniteDuration, backoff: Int)(
      implicit ec: ExecutionContext,
      scheduler: Scheduler): Future[T] =
    retryF(attempt, attempts, delay, backoff)((_, _) => ())

  /**
   * Given a function from Unit to Future, returns an internally retrying Future
   * The first attempt will be made immediately, each subsequent attempt will be made after 'delay' * 'backoff'
   * The callback function will be executed before each retry.
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry
   * If attempts are exhausted the returned future is simply the result of invoking attempt.
   * Note that the attempt and callback functions will be invoked on the given execution context for subsequent
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
   *   backoff = 2) { case (t, currentRetry) =>
   *     log.warning(
   *       s"""
   *         |Operation failed due to ${t.getMessage}.
   *         |Will retry $currentRetry times, after ${delay * math.pow(backoff, attempts - currentRetry + 1)}.
   *       """.stripMargin)
   * )
   * }}}
   */
  def retryF[T](attempt: () => Future[T], attempts: Int, delay: FiniteDuration, backoff: Int)(
      f: (Throwable, Int) => Unit)(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    try {
      if (attempts > 0 && backoff > 0) {
        attempt().recoverWith {
          case NonFatal(t) =>
            after(delay, scheduler) {
              f(t, attempts)
              retryF(attempt, attempts - 1, delay * backoff, backoff)(f)
            }
        }
      } else {
        attempt()
      }
    } catch {
      case NonFatal(error) => Future.failed(error)
    }
  }
}

object RetrySupport extends RetrySupport
