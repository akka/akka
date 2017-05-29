package akka.pattern

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration
import akka.actor.Scheduler

/**
 * This trait provides the retry utility function
 */
trait RetrySupport {

  /**
   * Given a function from Unit to Future, returns an internally retrying Future
   * The first attempt will be made immediately, each subsequent attempt will be made after 'delay'
   * A scheduler (eg context.system.scheduler) must be provided to delay each retry
   * If attempts are exhausted the returned future is simply the result of invoking attempt
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
  def retry[T](
    attempt:   () ⇒ Future[T],
    attempts:  Int,
    delay:     FiniteDuration,
    scheduler: Scheduler
  )(implicit ec: ExecutionContext): Future[T] = if (attempts > 0) {
    attempt() recoverWith {
      case _ ⇒ after(delay, scheduler) { retry(attempt, attempts - 1, delay, scheduler) }
    }
  } else after(delay, scheduler) { attempt() }

}

