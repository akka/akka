/*
 * Copyright (C) 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.pattern.RetrySupport.calculateExponentialBackoffDelay
import com.typesafe.config.Config

import java.time.Duration
import java.util.Optional
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

final case class RetrySettings(
    maxRetries: Int,
    delayFunction: Int => Option[FiniteDuration],
    shouldRetry: Throwable => Boolean = _ => true) {

  override def toString: String = {
    s"RetrySettings(maxRetries=$maxRetries, delayFunction=$delayFunction)"
  }

  def withExponentialBackoff(
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double): RetrySettings =
    RetrySettings(maxRetries, minBackoff, maxBackoff, randomFactor)

  def withExponentialBackoff(minBackoff: Duration, maxBackoff: Duration, randomFactor: Double): RetrySettings =
    RetrySettings.create(maxRetries, minBackoff, maxBackoff, randomFactor)

  def withDelay(fixedDelay: FiniteDuration): RetrySettings =
    RetrySettings(maxRetries, fixedDelay)

  def withDelay(fixedDelay: Duration): RetrySettings =
    RetrySettings.create(maxRetries, fixedDelay)

  def withDelay(delayFunction: Int => Option[FiniteDuration]): RetrySettings =
    copy(delayFunction = delayFunction)

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

  def withDecider(shouldRetry: Throwable => Boolean): RetrySettings =
    copy(shouldRetry = shouldRetry)

  def withDeciderFunction(shouldRetry: java.util.function.Function[Throwable, Boolean]): RetrySettings =
    copy(shouldRetry = shouldRetry.apply)
}

object RetrySettings {

  def apply(maxRetries: Int): RetrySettings = {
    // Start with a reasonable minimum backoff (e.g., 100 milliseconds)
    val minBackoff: FiniteDuration = 100.millis

    // Max backoff increases with maxRetries, capped to a sensible upper limit (e.g., 1 minute)
    val maxBackoff: FiniteDuration = if (maxRetries > 10) { //to avoid multiplication overflow
      1.minute
    } else {
      val base = minBackoff * math.pow(2, maxRetries).toLong
      base.min(1.minute)
    }

    // Random factor can scale slightly with maxRetries to add more jitter
    val randomFactor: Double = (0.1 + (maxRetries * 0.05)).min(1.0) // cap at 1.0

    apply(maxRetries, minBackoff, maxBackoff, randomFactor)
  }

  def create(maxRetries: Int): RetrySettings = {
    apply(maxRetries)
  }

  def apply(maxRetries: Int, fixedDelay: FiniteDuration): RetrySettings = {
    RetrySettings(maxRetries, FixedDelayFunction(fixedDelay))
  }

  def create(maxRetries: Int, fixedDelay: Duration): RetrySettings = {
    apply(maxRetries, fixedDelay.toScala)
  }

  def apply(
      maxRetries: Int,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double): RetrySettings =
    RetrySettings(maxRetries, ExponentialBackoffFunction(minBackoff, maxBackoff, randomFactor))

  def create(maxRetries: Int, minBackoff: Duration, maxBackoff: Duration, randomFactor: Double): RetrySettings = {
    apply(maxRetries, minBackoff.toScala, maxBackoff.toScala, randomFactor)
  }

  def apply(config: Config): RetrySettings = {
    val maxRetries = config.getInt("max-retries")
    if (config.hasPath("fixed-delay")) {
      val fixedDelay = config.getDuration("fixed-delay").toScala
      RetrySettings(maxRetries, fixedDelay)
    } else if (config.hasPath("min-backoff")) {
      val minBackoff = config.getDuration("min-backoff").toScala
      val maxBackoff = config.getDuration("max-backoff").toScala
      val randomFactor = config.getDouble("random-factor")
      RetrySettings(maxRetries, minBackoff, maxBackoff, randomFactor)
    } else
      RetrySettings(maxRetries)
  }

  def create(config: Config): RetrySettings = {
    apply(config)
  }

}

private[akka] final case class ExponentialBackoffFunction(
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    randomFactor: Double)
    extends Function1[Int, Option[FiniteDuration]] {

  override def apply(attempt: Int): Option[FiniteDuration] = {
    Some(calculateExponentialBackoffDelay(attempt, minBackoff, maxBackoff, randomFactor))
  }

  override def toString: String = {
    s"Exponential(minBackoff=$minBackoff, maxBackoff=$maxBackoff, randomFactor=$randomFactor)"
  }
}

private[akka] final case class FixedDelayFunction(delay: FiniteDuration)
    extends Function1[Int, Option[FiniteDuration]] {

  override def apply(attempt: Int): Option[FiniteDuration] = {
    Some(delay)
  }

  override def toString: String = {
    s"Fixed($delay)"
  }
}
