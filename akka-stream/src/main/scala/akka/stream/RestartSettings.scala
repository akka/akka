/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import scala.concurrent.duration.FiniteDuration

import akka.util.JavaDurationConverters._

final class RestartSettings private (
    val minBackoff: FiniteDuration,
    val maxBackoff: FiniteDuration,
    val randomFactor: Double,
    val maxRestarts: Int,
    val maxRestartsWithin: FiniteDuration) {

  /** Scala API: minimum (initial) duration until the child actor will started again, if it is terminated */
  def withMinBackoff(value: FiniteDuration): RestartSettings = copy(minBackoff = value)

  /** Java API: minimum (initial) duration until the child actor will started again, if it is terminated */
  def withMinBackoff(value: java.time.Duration): RestartSettings = copy(minBackoff = value.asScala)

  /** Scala API: the exponential back-off is capped to this duration */
  def withMaxBackoff(value: FiniteDuration): RestartSettings = copy(maxBackoff = value)

  /** Java API: the exponential back-off is capped to this duration */
  def withMaxBackoff(value: java.time.Duration): RestartSettings = copy(maxBackoff = value.asScala)

  /**
   * After calculation of the exponential back-off an additional random delay based on this factor is added
   * e.g. `0.2` adds up to `20%` delay.  In order to skip this additional delay pass in `0`
   */
  def withRandomFactor(value: Double): RestartSettings = copy(randomFactor = value)

  /** Scala API: The amount of restarts is capped to `count` within a timeframe of `within` */
  def withMaxRestarts(count: Int, within: FiniteDuration): RestartSettings =
    copy(maxRestarts = count, maxRestartsWithin = within)

  /** Java API: The amount of restarts is capped to `count` within a timeframe of `within` */
  def withMaxRestarts(count: Int, within: java.time.Duration): RestartSettings =
    copy(maxRestarts = count, maxRestartsWithin = within.asScala)

  override def toString: String =
    "RestartSettings(" +
    s"minBackoff=$minBackoff," +
    s"maxBackoff=$maxBackoff," +
    s"randomFactor=$randomFactor," +
    s"maxRestarts=$maxRestarts," +
    s"maxRestartsWithin=$maxRestartsWithin)"

  private def copy(
      minBackoff: FiniteDuration = minBackoff,
      maxBackoff: FiniteDuration = maxBackoff,
      randomFactor: Double = randomFactor,
      maxRestarts: Int = maxRestarts,
      maxRestartsWithin: FiniteDuration = maxRestartsWithin): RestartSettings =
    new RestartSettings(minBackoff, maxBackoff, randomFactor, maxRestarts, maxRestartsWithin)

}

object RestartSettings {

  /** Scala API */
  def apply(minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double): RestartSettings =
    new RestartSettings(
      minBackoff = minBackoff,
      maxBackoff = maxBackoff,
      randomFactor = randomFactor,
      maxRestarts = Int.MaxValue,
      maxRestartsWithin = minBackoff)

  /** Java API */
  def create(minBackoff: java.time.Duration, maxBackoff: java.time.Duration, randomFactor: Double): RestartSettings =
    new RestartSettings(
      minBackoff = minBackoff.asScala,
      maxBackoff = maxBackoff.asScala,
      randomFactor = randomFactor,
      maxRestarts = Int.MaxValue,
      maxRestartsWithin = minBackoff.asScala)
}
