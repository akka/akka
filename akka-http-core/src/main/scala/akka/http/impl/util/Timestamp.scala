/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import scala.concurrent.duration._

/**
 * INTERNAL API
 *
 * Helper for dealing with points in time rather than durations.
 * We mark it private[http] because we don't want to support it as public API.
 */
private[http] class Timestamp private (val timestampNanos: Long) extends AnyVal {

  def +(period: Duration): Timestamp =
    if (isNever) this
    else if (!period.isFinite()) Timestamp.never
    else new Timestamp(timestampNanos + period.toNanos)

  def -(other: Timestamp): Duration =
    if (isNever) Duration.Inf
    else if (other.isNever) Duration.MinusInf
    else (timestampNanos - other.timestampNanos).nanos

  def isPast: Boolean = System.nanoTime() >= timestampNanos
  def isPast(now: Timestamp): Boolean = now.timestampNanos >= timestampNanos
  def isFuture: Boolean = !isPast

  def isFinite: Boolean = timestampNanos < Long.MaxValue
  def isNever: Boolean = timestampNanos == Long.MaxValue
}

private[http] object Timestamp {
  def now: Timestamp = new Timestamp(System.nanoTime())
  def never: Timestamp = new Timestamp(Long.MaxValue)

  implicit object Ordering extends Ordering[Timestamp] {
    def compare(x: Timestamp, y: Timestamp): Int = math.signum(x.timestampNanos - y.timestampNanos).toInt
  }
}