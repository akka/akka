/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import java.util.concurrent.TimeUnit

object Duration {
  def apply(length: Long, unit: TimeUnit) = new Duration(length, unit)
  def apply(length: Long, unit: String) = new Duration(length, timeUnit(unit))

  def timeUnit(unit: String) = unit.toLowerCase match {
    case "nanoseconds" | "nanos" | "nanosecond" | "nano" => TimeUnit.NANOSECONDS
    case "microseconds" | "micros" |  "microsecond" | "micro" => TimeUnit.MICROSECONDS
    case "milliseconds" | "millis" | "millisecond" | "milli" => TimeUnit.MILLISECONDS
    case _ => TimeUnit.SECONDS
  }
}

/**
 * Utility for working with java.util.concurrent.TimeUnit durations.
 *
 * <p/>
 * Examples of usage from Java:
 * <pre>
 * import akka.util.Duration;
 * import java.util.concurrent.TimeUnit;
 *
 * Duration duration = new Duration(100, TimeUnit.MILLISECONDS);
 * Duration duration = new Duration(5, "seconds");
 *
 * duration.toNanos();
 * </pre>
 *
 * <p/>
 * Examples of usage from Scala:
 * <pre>
 * import akka.util.Duration
 * import java.util.concurrent.TimeUnit
 *
 * val duration = Duration(100, TimeUnit.MILLISECONDS)
 * val duration = Duration(100, "millis")
 *
 * duration.toNanos
 * </pre>
 *
 * <p/>
 * Implicits are also provided for Int and Long. Example usage:
 * <pre>
 * import akka.util.duration._
 *
 * val duration = 100.millis
 * </pre>
 */
class Duration(val length: Long, val unit: TimeUnit) {
  def this(length: Long, unit: String) = this(length, Duration.timeUnit(unit))
  def toNanos = unit.toNanos(length)
  def toMicros = unit.toMicros(length)
  def toMillis = unit.toMillis(length)
  def toSeconds = unit.toSeconds(length)
  override def toString = "Duration(" + length + ", " + unit + ")"
}

package object duration {
  implicit def intToDurationInt(n: Int) = new DurationInt(n)
  implicit def longToDurationLong(n: Long) = new DurationLong(n)
}

class DurationInt(n: Int) {
  def nanoseconds = Duration(n, TimeUnit.NANOSECONDS)
  def nanos = Duration(n, TimeUnit.NANOSECONDS)
  def nanosecond = Duration(n, TimeUnit.NANOSECONDS)
  def nano = Duration(n, TimeUnit.NANOSECONDS)

  def microseconds = Duration(n, TimeUnit.MICROSECONDS)
  def micros =  Duration(n, TimeUnit.MICROSECONDS)
  def microsecond = Duration(n, TimeUnit.MICROSECONDS)
  def micro =  Duration(n, TimeUnit.MICROSECONDS)

  def milliseconds =  Duration(n, TimeUnit.MILLISECONDS)
  def millis = Duration(n, TimeUnit.MILLISECONDS)
  def millisecond =  Duration(n, TimeUnit.MILLISECONDS)
  def milli = Duration(n, TimeUnit.MILLISECONDS)

  def seconds = Duration(n, TimeUnit.SECONDS)
  def second = Duration(n, TimeUnit.SECONDS)
}

class DurationLong(n: Long) {
  def nanoseconds = Duration(n, TimeUnit.NANOSECONDS)
  def nanos = Duration(n, TimeUnit.NANOSECONDS)
  def nanosecond = Duration(n, TimeUnit.NANOSECONDS)
  def nano = Duration(n, TimeUnit.NANOSECONDS)

  def microseconds = Duration(n, TimeUnit.MICROSECONDS)
  def micros =  Duration(n, TimeUnit.MICROSECONDS)
  def microsecond = Duration(n, TimeUnit.MICROSECONDS)
  def micro =  Duration(n, TimeUnit.MICROSECONDS)

  def milliseconds =  Duration(n, TimeUnit.MILLISECONDS)
  def millis = Duration(n, TimeUnit.MILLISECONDS)
  def millisecond =  Duration(n, TimeUnit.MILLISECONDS)
  def milli = Duration(n, TimeUnit.MILLISECONDS)

  def seconds = Duration(n, TimeUnit.SECONDS)
  def second = Duration(n, TimeUnit.SECONDS)
}
