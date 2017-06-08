/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.annotation.InternalApi
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration

object SupervisorStrategy {

  /**
   * Resume means keeping the same state as before the exception was
   * thrown and is thus less safe than `restart`.
   */
  val resume: SupervisorStrategy = Resume(loggingEnabled = true)

  /**
   * Restart immediately without any limit on number of restart retries.
   */
  val restart: SupervisorStrategy = Restart(-1, Duration.Zero, loggingEnabled = true)

  /**
   * Restart with a limit of number of restart retries.
   * The number of restarts are limited to a number of restart attempts (`maxNrOfRetries`)
   * within a time range (`withinTimeRange`). When the time window has elapsed without reaching
   * `maxNrOfRetries` the restart count is reset.
   *
   * @param maxNrOfRetries the number of times a child actor is allowed to be restarted,
   *   if the limit is exceeded the child actor is stopped
   * @param withinTimeRange duration of the time window for maxNrOfRetries
   */
  def restartWithLimit(maxNrOfRetries: Int, withinTimeRange: FiniteDuration): SupervisorStrategy =
    Restart(maxNrOfRetries, withinTimeRange, loggingEnabled = true)

  /**
   * It supports exponential back-off between the given `minBackoff` and
   * `maxBackoff` durations. For example, if `minBackoff` is 3 seconds and
   * `maxBackoff` 30 seconds the start attempts will be delayed with
   * 3, 6, 12, 24, 30, 30 seconds. The exponential back-off counter is reset
   * if the actor is not terminated within the `minBackoff` duration.
   *
   * In addition to the calculated exponential back-off an additional
   * random delay based the given `randomFactor` is added, e.g. 0.2 adds up to 20%
   * delay. The reason for adding a random delay is to avoid that all failing
   * actors hit the backend resource at the same time.
   *
   * During the back-off incoming messages are dropped.
   *
   * If no new exception occurs within the `minBackoff` duration the exponentially
   * increased back-off timeout is reset.
   *
   * @param minBackoff minimum (initial) duration until the child actor will
   *   started again, if it is terminated
   * @param maxBackoff the exponential back-off is capped to this duration
   * @param randomFactor after calculation of the exponential back-off an additional
   *   random delay based on this factor is added, e.g. `0.2` adds up to `20%` delay.
   *   In order to skip this additional delay pass in `0`.
   */
  def restartWithBackoff(
    minBackoff:   FiniteDuration,
    maxBackoff:   FiniteDuration,
    randomFactor: Double): BackoffSupervisorStrategy =
    new Backoff(minBackoff, maxBackoff, randomFactor, resetBackoffAfter = minBackoff, loggingEnabled = true)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] case class Resume(loggingEnabled: Boolean) extends SupervisorStrategy {
    override def withLoggingEnabled(enabled: Boolean): SupervisorStrategy =
      copy(loggingEnabled = enabled)
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final case class Restart(
    maxNrOfRetries:  Int,
    withinTimeRange: FiniteDuration,
    loggingEnabled:  Boolean) extends SupervisorStrategy {

    override def withLoggingEnabled(enabled: Boolean): SupervisorStrategy =
      copy(loggingEnabled = enabled)
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final case class Backoff(
    minBackoff:        FiniteDuration,
    maxBackoff:        FiniteDuration,
    randomFactor:      Double,
    resetBackoffAfter: FiniteDuration,
    loggingEnabled:    Boolean) extends BackoffSupervisorStrategy {

    override def withLoggingEnabled(enabled: Boolean): SupervisorStrategy =
      copy(loggingEnabled = enabled)

    override def withResetBackoffAfter(timeout: FiniteDuration): BackoffSupervisorStrategy =
      copy(resetBackoffAfter = timeout)
  }
}

sealed abstract class SupervisorStrategy {
  def loggingEnabled: Boolean

  def withLoggingEnabled(on: Boolean): SupervisorStrategy
}

sealed abstract class BackoffSupervisorStrategy extends SupervisorStrategy {
  def resetBackoffAfter: FiniteDuration

  /**
   * The back-off algorithm is reset if the actor does not crash within the
   * specified `resetBackoffAfter`. By default, the `resetBackoffAfter` has
   * the same value as `minBackoff`.
   */
  def withResetBackoffAfter(timeout: FiniteDuration): BackoffSupervisorStrategy
}
