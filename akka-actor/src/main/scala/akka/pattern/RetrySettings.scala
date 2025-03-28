/*
 * Copyright (C) 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.pattern.RetrySupport.calculateExponentialBackoffDelay

import java.time.Duration
import java.util.Optional
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

case class RetrySettings(
    attempts: Int,
    delayFunction: Int => Option[FiniteDuration],
    shouldRetry: Throwable => Boolean = _ => true) {

  def withDelay(delayFunction: Int => Option[FiniteDuration]): RetrySettings = {
    copy(delayFunction = delayFunction)
  }

  def withDelayFunction(delayFunction: java.util.function.IntFunction[Optional[java.time.Duration]]): RetrySettings = {
    val scalaFunc: Int => Option[FiniteDuration] = attempt => {
      val javaDuration = delayFunction.apply(attempt)
      if (javaDuration.isPresent)
        Some(javaDuration.get.toScala)
      else
        None
    }
    withDelay(scalaFunc)
  }

  def withDecider(shouldRetry: Throwable => Boolean): RetrySettings = {
    copy(shouldRetry = shouldRetry)
  }

  def withDeciderFunction(shouldRetry: java.util.function.Function[Throwable, Boolean]): RetrySettings = {
    copy(shouldRetry = shouldRetry.apply)
  }
}

object RetrySettings {

  def apply(attempts: Int): RetrySettings = {
    // Start with a reasonable minimum backoff (e.g., 100 milliseconds)
    val minBackoff: FiniteDuration = 100.millis

    // Max backoff increases with attempts, capped to a sensible upper limit (e.g., 1 minute)
    val maxBackoff: FiniteDuration = {
      val base = minBackoff * math.pow(2, attempts).toLong
      base.min(1.minute)
    }

    // Random factor can scale slightly with attempts to add more jitter
    val randomFactor: Double = 0.1 + (attempts * 0.05).min(1.0) // cap at 1.0

    apply(attempts, minBackoff, maxBackoff, randomFactor)
  }

  def create(attempts: Int): RetrySettings = {
    apply(attempts)
  }

  def apply(attempts: Int, fixedDelay: FiniteDuration): RetrySettings = {
    RetrySettings(attempts, _ => Some(fixedDelay))
  }

  def create(attempts: Int, fixedDelay: Duration): RetrySettings = {
    apply(attempts, fixedDelay.toScala)
  }

  def apply(
      attempts: Int,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double): RetrySettings = {
    new RetrySettings(
      attempts,
      attempted => Some(calculateExponentialBackoffDelay(attempted, minBackoff, maxBackoff, randomFactor))) {

      override def toString: String = {
        s"RetrySettings(attempts=${this.attempts}, minBackoff=$minBackoff, maxBackoff=$maxBackoff, randomFactor=$randomFactor)"
      }
    }
  }

  def create(attempts: Int, minBackoff: Duration, maxBackoff: Duration, randomFactor: Double): RetrySettings = {
    apply(attempts, minBackoff.toScala, maxBackoff.toScala, randomFactor)
  }

}
