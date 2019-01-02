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
   *   delay = 2 seconds,
   *   scheduler = context.system.scheduler
   * )
   * }}}
   */
  def retry[T](attempt: () ⇒ Future[T], attempts: Int, delay: FiniteDuration)(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    try {
      if (attempts > 0) {
        attempt() recoverWith {
          case NonFatal(_) ⇒ after(delay, scheduler) {
            retry(attempt, attempts - 1, delay)
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

object RetrySupport extends RetrySupport
