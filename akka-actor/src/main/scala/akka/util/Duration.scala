/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.util.concurrent.TimeUnit
import TimeUnit._
import java.lang.{ Double ⇒ JDouble }

//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class Deadline private (time: Duration) {
  def +(other: Duration): Deadline = copy(time = time + other)
  def -(other: Duration): Deadline = copy(time = time - other)
  def -(other: Deadline): Duration = time - other.time
  def timeLeft: Duration = this - Deadline.now
  def hasTimeLeft(): Boolean = !isOverdue() //Code reuse FTW
  def isOverdue(): Boolean = (time.toNanos - System.nanoTime()) < 0
}
object Deadline {
  def now: Deadline = Deadline(Duration(System.nanoTime, NANOSECONDS))
}

object Duration {
  implicit def timeLeft(implicit d: Deadline): Duration = d.timeLeft

  def apply(length: Long, unit: TimeUnit): FiniteDuration = new FiniteDuration(length, unit)
  def apply(length: Double, unit: TimeUnit): FiniteDuration = fromNanos(unit.toNanos(1) * length)
  def apply(length: Long, unit: String): FiniteDuration = new FiniteDuration(length, timeUnit(unit))

  def fromNanos(nanos: Long): FiniteDuration = {
    if (nanos % 86400000000000L == 0) {
      Duration(nanos / 86400000000000L, DAYS)
    } else if (nanos % 3600000000000L == 0) {
      Duration(nanos / 3600000000000L, HOURS)
    } else if (nanos % 60000000000L == 0) {
      Duration(nanos / 60000000000L, MINUTES)
    } else if (nanos % 1000000000L == 0) {
      Duration(nanos / 1000000000L, SECONDS)
    } else if (nanos % 1000000L == 0) {
      Duration(nanos / 1000000L, MILLISECONDS)
    } else if (nanos % 1000L == 0) {
      Duration(nanos / 1000L, MICROSECONDS)
    } else {
      Duration(nanos, NANOSECONDS)
    }
  }

  def fromNanos(nanos: Double): FiniteDuration = {
    if (nanos > Long.MaxValue || nanos < Long.MinValue)
      throw new IllegalArgumentException("trying to construct too large duration with " + nanos + "ns")
    fromNanos((nanos + 0.5).asInstanceOf[Long])
  }

  /**
   * Construct a Duration by parsing a String. In case of a format error, a
   * RuntimeException is thrown. See `unapply(String)` for more information.
   */
  def apply(s: String): Duration = unapply(s) getOrElse sys.error("format error")

  /**
   * Deconstruct a Duration into length and unit if it is finite.
   */
  def unapply(d: Duration): Option[(Long, TimeUnit)] = {
    if (d.finite_?) {
      Some((d.length, d.unit))
    } else {
      None
    }
  }

  private val RE = ("""^\s*(-?\d+(?:\.\d+)?)\s*""" + // length part
    "(?:" + // units are distinguished in separate match groups
    "(d|day|days)|" +
    "(h|hour|hours)|" +
    "(min|minute|minutes)|" +
    "(s|sec|second|seconds)|" +
    "(ms|milli|millis|millisecond|milliseconds)|" +
    "(µs|micro|micros|microsecond|microseconds)|" +
    "(ns|nano|nanos|nanosecond|nanoseconds)" +
    """)\s*$""").r // close the non-capturing group
  private val REinf = """^\s*Inf\s*$""".r
  private val REminf = """^\s*(?:-\s*|Minus)Inf\s*""".r

  /**
   * Parse String, return None if no match. Format is `"<length><unit>"`, where
   * whitespace is allowed before, between and after the parts. Infinities are
   * designated by `"Inf"` and `"-Inf"` or `"MinusInf"`.
   */
  def unapply(s: String): Option[Duration] = s match {
    case RE(length, d, h, m, s, ms, mus, ns) ⇒
      if (d ne null) Some(Duration(JDouble.parseDouble(length), DAYS)) else if (h ne null) Some(Duration(JDouble.parseDouble(length), HOURS)) else if (m ne null) Some(Duration(JDouble.parseDouble(length), MINUTES)) else if (s ne null) Some(Duration(JDouble.parseDouble(length), SECONDS)) else if (ms ne null) Some(Duration(JDouble.parseDouble(length), MILLISECONDS)) else if (mus ne null) Some(Duration(JDouble.parseDouble(length), MICROSECONDS)) else if (ns ne null) Some(Duration(JDouble.parseDouble(length), NANOSECONDS)) else
        sys.error("made some error in regex (should not be possible)")
    case REinf()  ⇒ Some(Inf)
    case REminf() ⇒ Some(MinusInf)
    case _        ⇒ None
  }

  /**
   * Parse TimeUnit from string representation.
   */
  def timeUnit(unit: String) = unit.toLowerCase match {
    case "d" | "day" | "days"                                       ⇒ DAYS
    case "h" | "hour" | "hours"                                     ⇒ HOURS
    case "min" | "minute" | "minutes"                               ⇒ MINUTES
    case "s" | "sec" | "second" | "seconds"                         ⇒ SECONDS
    case "ms" | "milli" | "millis" | "millisecond" | "milliseconds" ⇒ MILLISECONDS
    case "µs" | "micro" | "micros" | "microsecond" | "microseconds" ⇒ MICROSECONDS
    case "ns" | "nano" | "nanos" | "nanosecond" | "nanoseconds"     ⇒ NANOSECONDS
  }

  val Zero: FiniteDuration = new FiniteDuration(0, NANOSECONDS)

  val Undefined: Duration = new Duration with Infinite {
    override def toString = "Duration.Undefined"
    override def equals(other: Any) = other.asInstanceOf[AnyRef] eq this
    override def +(other: Duration): Duration = throw new IllegalArgumentException("cannot add Undefined duration")
    override def -(other: Duration): Duration = throw new IllegalArgumentException("cannot subtract Undefined duration")
    override def *(factor: Double): Duration = throw new IllegalArgumentException("cannot multiply Undefined duration")
    override def /(factor: Double): Duration = throw new IllegalArgumentException("cannot divide Undefined duration")
    override def /(other: Duration): Double = throw new IllegalArgumentException("cannot divide Undefined duration")
    def compare(other: Duration) = throw new IllegalArgumentException("cannot compare Undefined duration")
    def unary_- : Duration = throw new IllegalArgumentException("cannot negate Undefined duration")
  }

  trait Infinite {
    this: Duration ⇒

    def +(other: Duration): Duration =
      other match {
        case _: this.type ⇒ this
        case _: Infinite  ⇒ throw new IllegalArgumentException("illegal addition of infinities")
        case _            ⇒ this
      }
    def -(other: Duration): Duration =
      other match {
        case _: this.type ⇒ throw new IllegalArgumentException("illegal subtraction of infinities")
        case _            ⇒ this
      }
    def *(factor: Double): Duration = this
    def /(factor: Double): Duration = this
    def /(other: Duration): Double =
      other match {
        case _: Infinite ⇒ throw new IllegalArgumentException("illegal division of infinities")
        // maybe questionable but pragmatic: Inf / 0 => Inf
        case x           ⇒ Double.PositiveInfinity * (if ((this > Zero) ^ (other >= Zero)) -1 else 1)
      }

    def finite_? = false

    def length: Long = throw new IllegalArgumentException("length not allowed on infinite Durations")
    def unit: TimeUnit = throw new IllegalArgumentException("unit not allowed on infinite Durations")
    def toNanos: Long = throw new IllegalArgumentException("toNanos not allowed on infinite Durations")
    def toMicros: Long = throw new IllegalArgumentException("toMicros not allowed on infinite Durations")
    def toMillis: Long = throw new IllegalArgumentException("toMillis not allowed on infinite Durations")
    def toSeconds: Long = throw new IllegalArgumentException("toSeconds not allowed on infinite Durations")
    def toMinutes: Long = throw new IllegalArgumentException("toMinutes not allowed on infinite Durations")
    def toHours: Long = throw new IllegalArgumentException("toHours not allowed on infinite Durations")
    def toDays: Long = throw new IllegalArgumentException("toDays not allowed on infinite Durations")
    def toUnit(unit: TimeUnit): Double = throw new IllegalArgumentException("toUnit not allowed on infinite Durations")

    def printHMS = toString
  }

  /**
   * Infinite duration: greater than any other and not equal to any other,
   * including itself.
   */
  val Inf: Duration = new Duration with Infinite {
    override def toString: String = "Duration.Inf"
    def compare(other: Duration): Int = if (other eq this) 0 else 1
    def unary_- : Duration = MinusInf
  }

  /**
   * Infinite negative duration: lesser than any other and not equal to any other,
   * including itself.
   */
  val MinusInf: Duration = new Duration with Infinite {
    override def toString = "Duration.MinusInf"
    def compare(other: Duration): Int = if (other eq this) 0 else -1
    def unary_- : Duration = Inf
  }

  // Java Factories
  def create(length: Long, unit: TimeUnit): FiniteDuration = apply(length, unit)
  def create(length: Double, unit: TimeUnit): FiniteDuration = apply(length, unit)
  def create(length: Long, unit: String): FiniteDuration = apply(length, unit)
  def parse(s: String): Duration = unapply(s).get

  implicit object DurationIsOrdered extends Ordering[Duration] {
    def compare(a: Duration, b: Duration): Int = a compare b
  }
}

/**
 * Utility for working with java.util.concurrent.TimeUnit durations.
 *
 * <p/>
 * Examples of usage from Java:
 * <pre>
 * import akka.util.FiniteDuration;
 * import java.util.concurrent.TimeUnit;
 *
 * Duration duration = new FiniteDuration(100, MILLISECONDS);
 * Duration duration = new FiniteDuration(5, "seconds");
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
 * val duration = Duration(100, MILLISECONDS)
 * val duration = Duration(100, "millis")
 *
 * duration.toNanos
 * duration < 1.second
 * duration <= Duration.Inf
 * </pre>
 *
 * <p/>
 * Implicits are also provided for Int, Long and Double. Example usage:
 * <pre>
 * import akka.util.duration._
 *
 * val duration = 100 millis
 * </pre>
 *
 * Extractors, parsing and arithmetic are also included:
 * <pre>
 * val d = Duration("1.2 µs")
 * val Duration(length, unit) = 5 millis
 * val d2 = d * 2.5
 * val d3 = d2 + 1.millisecond
 * </pre>
 */
//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
abstract class Duration extends Serializable with Ordered[Duration] {
  def length: Long
  def unit: TimeUnit
  def toNanos: Long
  def toMicros: Long
  def toMillis: Long
  def toSeconds: Long
  def toMinutes: Long
  def toHours: Long
  def toDays: Long
  def toUnit(unit: TimeUnit): Double
  def printHMS: String
  def +(other: Duration): Duration
  def -(other: Duration): Duration
  def *(factor: Double): Duration
  def /(factor: Double): Duration
  def /(other: Duration): Double
  def unary_- : Duration
  def finite_? : Boolean
  def min(other: Duration): Duration = if (this < other) this else other
  def max(other: Duration): Duration = if (this > other) this else other
  def sleep(): Unit = Thread.sleep(toMillis)
  def fromNow: Deadline = Deadline.now + this

  // Java API
  def lt(other: Duration): Boolean = this < other
  def lteq(other: Duration): Boolean = this <= other
  def gt(other: Duration): Boolean = this > other
  def gteq(other: Duration): Boolean = this >= other
  def plus(other: Duration): Duration = this + other
  def minus(other: Duration): Duration = this - other
  def mul(factor: Double): Duration = this * factor
  def div(factor: Double): Duration = this / factor
  def div(other: Duration): Double = this / other
  def neg(): Duration = -this
  def isFinite(): Boolean = finite_?
}

object FiniteDuration {
  implicit object FiniteDurationIsOrdered extends Ordering[FiniteDuration] {
    def compare(a: FiniteDuration, b: FiniteDuration) = a compare b
  }
}

//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
class FiniteDuration(val length: Long, val unit: TimeUnit) extends Duration {
  import Duration._

  require {
    unit match {
      /*
       * sorted so that the first cases should be most-used ones, because enum
       * is checked one after the other.
       */
      case NANOSECONDS  ⇒ true
      case MICROSECONDS ⇒ length <= 9223372036854775L && length >= -9223372036854775L
      case MILLISECONDS ⇒ length <= 9223372036854L && length >= -9223372036854L
      case SECONDS      ⇒ length <= 9223372036L && length >= -9223372036L
      case MINUTES      ⇒ length <= 153722867L && length >= -153722867L
      case HOURS        ⇒ length <= 2562047L && length >= -2562047L
      case DAYS         ⇒ length <= 106751L && length >= -106751L
      case _ ⇒
        val v = unit.convert(length, DAYS)
        v <= 106751L && v >= -106751L
    }
  }

  def this(length: Long, unit: String) = this(length, Duration.timeUnit(unit))

  def toNanos = unit.toNanos(length)
  def toMicros = unit.toMicros(length)
  def toMillis = unit.toMillis(length)
  def toSeconds = unit.toSeconds(length)
  def toMinutes = unit.toMinutes(length)
  def toHours = unit.toHours(length)
  def toDays = unit.toDays(length)
  def toUnit(u: TimeUnit) = toNanos.toDouble / NANOSECONDS.convert(1, u)

  override def toString = this match {
    case Duration(1, DAYS)         ⇒ "1 day"
    case Duration(x, DAYS)         ⇒ x + " days"
    case Duration(1, HOURS)        ⇒ "1 hour"
    case Duration(x, HOURS)        ⇒ x + " hours"
    case Duration(1, MINUTES)      ⇒ "1 minute"
    case Duration(x, MINUTES)      ⇒ x + " minutes"
    case Duration(1, SECONDS)      ⇒ "1 second"
    case Duration(x, SECONDS)      ⇒ x + " seconds"
    case Duration(1, MILLISECONDS) ⇒ "1 millisecond"
    case Duration(x, MILLISECONDS) ⇒ x + " milliseconds"
    case Duration(1, MICROSECONDS) ⇒ "1 microsecond"
    case Duration(x, MICROSECONDS) ⇒ x + " microseconds"
    case Duration(1, NANOSECONDS)  ⇒ "1 nanosecond"
    case Duration(x, NANOSECONDS)  ⇒ x + " nanoseconds"
  }

  def printHMS = "%02d:%02d:%06.3f".format(toHours, toMinutes % 60, toMillis / 1000d % 60)

  def compare(other: Duration) =
    if (other.finite_?) {
      val me = toNanos
      val o = other.toNanos
      if (me > o) 1 else if (me < o) -1 else 0
    } else -other.compare(this)

  private def add(a: Long, b: Long): Long = {
    val c = a + b
    // check if the signs of the top bit of both summands differ from the sum
    if (((a ^ c) & (b ^ c)) < 0) throw new IllegalArgumentException("")
    else c
  }

  def +(other: Duration): Duration = if (!other.finite_?) other else fromNanos(add(toNanos, other.toNanos))

  def -(other: Duration): Duration = if (!other.finite_?) other else fromNanos(add(toNanos, -other.toNanos))

  def *(factor: Double): FiniteDuration = fromNanos(toNanos.toDouble * factor)

  def /(factor: Double): FiniteDuration = fromNanos(toNanos.toDouble / factor)

  def /(other: Duration): Double = if (other.finite_?) toNanos.toDouble / other.toNanos else 0

  def unary_- : FiniteDuration = Duration(-length, unit)

  def finite_? : Boolean = true

  override def equals(other: Any) =
    (other.asInstanceOf[AnyRef] eq this) || other.isInstanceOf[FiniteDuration] &&
      toNanos == other.asInstanceOf[FiniteDuration].toNanos

  override def hashCode = {
    val nanos = toNanos
    (nanos ^ (nanos >> 32)).asInstanceOf[Int]
  }
}

private[akka] trait DurationOps {
  import duration.Classifier
  protected def from(timeUnit: TimeUnit): FiniteDuration
  def nanoseconds: FiniteDuration = from(NANOSECONDS)
  def nanos: FiniteDuration = from(NANOSECONDS)
  def nanosecond: FiniteDuration = from(NANOSECONDS)
  def nano: FiniteDuration = from(NANOSECONDS)

  def microseconds: FiniteDuration = from(MICROSECONDS)
  def micros: FiniteDuration = from(MICROSECONDS)
  def microsecond: FiniteDuration = from(MICROSECONDS)
  def micro: FiniteDuration = from(MICROSECONDS)

  def milliseconds: FiniteDuration = from(MILLISECONDS)
  def millis: FiniteDuration = from(MILLISECONDS)
  def millisecond: FiniteDuration = from(MILLISECONDS)
  def milli: FiniteDuration = from(MILLISECONDS)

  def seconds: FiniteDuration = from(SECONDS)
  def second: FiniteDuration = from(SECONDS)

  def minutes: FiniteDuration = from(MINUTES)
  def minute: FiniteDuration = from(MINUTES)

  def hours: FiniteDuration = from(HOURS)
  def hour: FiniteDuration = from(HOURS)

  def days: FiniteDuration = from(DAYS)
  def day: FiniteDuration = from(DAYS)

  def nanoseconds[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(NANOSECONDS))
  def nanos[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(NANOSECONDS))
  def nanosecond[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(NANOSECONDS))
  def nano[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(NANOSECONDS))

  def microseconds[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MICROSECONDS))
  def micros[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MICROSECONDS))
  def microsecond[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MICROSECONDS))
  def micro[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MICROSECONDS))

  def milliseconds[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MILLISECONDS))
  def millis[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MILLISECONDS))
  def millisecond[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MILLISECONDS))
  def milli[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MILLISECONDS))

  def seconds[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(SECONDS))
  def second[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(SECONDS))

  def minutes[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MINUTES))
  def minute[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(MINUTES))

  def hours[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(HOURS))
  def hour[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(HOURS))

  def days[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(DAYS))
  def day[C, CC <: Classifier[C]](c: C)(implicit ev: CC): CC#R = ev.convert(from(DAYS))
}

class DurationInt(n: Int) extends DurationOps {
  override protected def from(timeUnit: TimeUnit): FiniteDuration = Duration(n, timeUnit)
}

class DurationLong(n: Long) extends DurationOps {
  override protected def from(timeUnit: TimeUnit): FiniteDuration = Duration(n, timeUnit)
}

class DurationDouble(d: Double) extends DurationOps {
  override protected def from(timeUnit: TimeUnit): FiniteDuration = Duration(d, timeUnit)
}

//TODO add @SerialVersionUID(1L) when SI-4804 is fixed
case class Timeout(duration: Duration) {
  def this(timeout: Long) = this(Duration(timeout, TimeUnit.MILLISECONDS))
  def this(length: Long, unit: TimeUnit) = this(Duration(length, unit))
}

/**
 * A Timeout is a wrapper on top of Duration to be more specific about what the duration means.
 */
object Timeout {

  /**
   * A timeout with zero duration, will cause most requests to always timeout.
   */
  val zero: Timeout = new Timeout(Duration.Zero)

  /**
   * A Timeout with infinite duration. Will never timeout. Use extreme caution with this
   * as it may cause memory leaks, blocked threads, or may not even be supported by
   * the receiver, which would result in an exception.
   */
  val never: Timeout = new Timeout(Duration.Inf)

  def apply(timeout: Long): Timeout = new Timeout(timeout)
  def apply(length: Long, unit: TimeUnit): Timeout = new Timeout(length, unit)

  implicit def durationToTimeout(duration: Duration): Timeout = new Timeout(duration)
  implicit def intToTimeout(timeout: Int): Timeout = new Timeout(timeout)
  implicit def longToTimeout(timeout: Long): Timeout = new Timeout(timeout)
}
