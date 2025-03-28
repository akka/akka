/*
 * Copyright (C) 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import java.time.Duration
import java.util.Optional
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

sealed trait RetrySettings {}

object RetrySettings {

  case class RetrySettingsBuilder(attempts: Int) {
    def withFixedDelay(fixedDelay: FiniteDuration): RetrySettings = {
      FixedDelayRetrySettings(attempts, fixedDelay)
    }

    def withFixedDelay(fixedDelay: Duration): RetrySettings = {
      FixedDelayRetrySettings(attempts, fixedDelay.toScala)
    }

    def withBackoff(minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double): RetrySettings = {
      BackoffRetrySettings(attempts, minBackoff, maxBackoff, randomFactor)
    }

    def withBackoff(minBackoff: Duration, maxBackoff: Duration, randomFactor: Double): RetrySettings = {
      BackoffRetrySettings(attempts, minBackoff.toScala, maxBackoff.toScala, randomFactor)
    }

    def withBackoff(): RetrySettings = {
      // Start with a reasonable minimum backoff (e.g., 100 milliseconds)
      val minBackoff: FiniteDuration = 100.millis

      // Max backoff increases with attempts, capped to a sensible upper limit (e.g., 1 minute)
      val maxBackoff: FiniteDuration = {
        val base = minBackoff * math.pow(2, attempts).toLong
        base.min(1.minute)
      }

      // Random factor can scale slightly with attempts to add more jitter
      val randomFactor: Double = 0.1 + (attempts * 0.05).min(1.0) // cap at 1.0

      BackoffRetrySettings(attempts, minBackoff, maxBackoff, randomFactor)
    }

    def withDynamicDelay(delayFunction: Int => Option[FiniteDuration]): RetrySettings = {
      DynamicRetrySettings(attempts, delayFunction)
    }

    def withDynamicDelayFunction(
        delayFunction: java.util.function.IntFunction[Optional[java.time.Duration]]): RetrySettings = {
      val scalaFunc: Int => Option[FiniteDuration] = attempt => {
        val javaDuration = delayFunction.apply(attempt)
        if (javaDuration.isPresent)
          Some(javaDuration.get.toScala)
        else
          None
      }
      withDynamicDelay(scalaFunc)
    }

    def withDecider(shouldRetry: Throwable => Boolean): RetrySettings = {
      DeciderRetrySettings(attempts, shouldRetry)
    }

    def withDeciderFunction(shouldRetry: java.util.function.Function[Throwable, Boolean]): RetrySettings = {
      DeciderRetrySettings(attempts, shouldRetry.apply)
    }
  }

  case class FixedDelayRetrySettings(
      attempts: Int,
      fixedDelay: FiniteDuration,
      shouldRetry: Throwable => Boolean = _ => true)
      extends RetrySettings {

    def withDecider(shouldRetry: Throwable => Boolean): RetrySettings = {
      copy(shouldRetry = shouldRetry)
    }

    def withDeciderFunction(shouldRetry: java.util.function.Function[Throwable, Boolean]): RetrySettings = {
      copy(shouldRetry = shouldRetry.apply)
    }
  }
  case class BackoffRetrySettings(
      attempts: Int,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      shouldRetry: Throwable => Boolean = _ => true)
      extends RetrySettings {

    def withDecider(shouldRetry: Throwable => Boolean): RetrySettings = {
      copy(shouldRetry = shouldRetry)
    }

    def withDeciderFunction(shouldRetry: java.util.function.Function[Throwable, Boolean]): RetrySettings = {
      copy(shouldRetry = shouldRetry.apply)
    }
  }
  case class DynamicRetrySettings(
      attempts: Int,
      delayFunction: Int => Option[FiniteDuration],
      shouldRetry: Throwable => Boolean = _ => true)
      extends RetrySettings {

    def withDecider(shouldRetry: Throwable => Boolean): RetrySettings = {
      copy(shouldRetry = shouldRetry)
    }

    def withDeciderFunction(shouldRetry: java.util.function.Function[Throwable, Boolean]): RetrySettings = {
      copy(shouldRetry = shouldRetry.apply)
    }
  }

  case class DeciderRetrySettings(attempts: Int, shouldRetry: Throwable => Boolean) extends RetrySettings

  def attempts(attempts: Int): RetrySettingsBuilder = {
    RetrySettingsBuilder(attempts)
  }

}
