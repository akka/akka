/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import java.util.concurrent.TimeUnit
import TimeUnit._
import java.lang.{Long => JLong, Double => JDouble}

object Duration {
  def apply(length: Long, unit: TimeUnit) : Duration = new FiniteDuration(length, unit)
  def apply(length: Double, unit: TimeUnit) : Duration = new FiniteDuration(length, unit)
  def apply(length: Long, unit: String) : Duration = new FiniteDuration(length, timeUnit(unit))

  /**
   * Construct a Duration by parsing a String. In case of a format error, a
   * RuntimeException is thrown. See `unapply(String)` for more information.
   */
  def apply(s : String) : Duration = unapply(s) getOrElse error("format error")

  /**
   * Deconstruct a Duration into length and unit if it is finite.
   */
  def unapply(d : Duration) : Option[(Long, TimeUnit)] = {
    if (d.finite_?) {
      Some((d.length, d.unit))
    } else {
      None
    }
  }

  private val RE = ("""^\s*(\d+(?:\.\d+)?)\s*"""+ // length part
    "(?:"+ // units are distinguished in separate match groups
    "(h|hour|hours)|"+
    "(min|minute|minutes)|"+
    "(s|sec|second|seconds)|"+
    "(ms|milli|millis|millisecond|milliseconds)|"+
    "(µs|micro|micros|microsecond|microseconds)|"+
    "(ns|nano|nanos|nanosecond|nanoseconds)"+
    """)\s*$""").r // close the non-capturing group
  private val REinf = """^\s*Inf\s*$""".r
  private val REminf = """^\s*(?:-\s*|Minus)Inf\s*""".r

  /**
   * Parse String, return None if no match. Format is `"<length><unit>"`, where
   * whitespace is allowed before, between and after the parts. Infinities are
   * designated by `"Inf"` and `"-Inf"` or `"MinusInf"`.
   */
  def unapply(s : String) : Option[Duration] = s match {
    case RE(length, h, m, s, ms, mus, ns) =>
      if (h ne null) Some(Duration(3600 * JDouble.parseDouble(length), SECONDS)) else
      if (m ne null) Some(Duration(60 * JDouble.parseDouble(length), SECONDS)) else
      if (s ne null) Some(Duration(1 * JDouble.parseDouble(length), SECONDS)) else
      if (ms ne null) Some(Duration(1 * JDouble.parseDouble(length), MILLISECONDS)) else
      if (mus ne null) Some(Duration(1 * JDouble.parseDouble(length), MICROSECONDS)) else
      if (ns ne null) Some(Duration(1 * JDouble.parseDouble(length), NANOSECONDS)) else
      error("made some error in regex (should not be possible)")
    case REinf() => Some(Inf)
    case REminf() => Some(MinusInf)
    case _ => None
  }

  /**
   * Parse TimeUnit from string representation.
   */
  def timeUnit(unit: String) = unit.toLowerCase match {
    case "nanoseconds" | "nanos" | "nanosecond" | "nano" => NANOSECONDS
    case "microseconds" | "micros" |  "microsecond" | "micro" => MICROSECONDS
    case "milliseconds" | "millis" | "millisecond" | "milli" => MILLISECONDS
    case _ => SECONDS
  }

  trait Infinite {
    this : Duration =>

	  override def equals(other : Any) = false

	  def +(other : Duration) : Duration = this
	  def -(other : Duration) : Duration = this
	  def *(other : Double) : Duration = this
	  def /(other : Double) : Duration = this
	
	  def finite_? = false
	
	  def length : Long = throw new IllegalArgumentException("length not allowed on infinite Durations")
	  def unit : TimeUnit = throw new IllegalArgumentException("unit not allowed on infinite Durations")
	  def toNanos : Long = throw new IllegalArgumentException("toNanos not allowed on infinite Durations")
	  def toMicros : Long = throw new IllegalArgumentException("toMicros not allowed on infinite Durations")
	  def toMillis : Long = throw new IllegalArgumentException("toMillis not allowed on infinite Durations")
	  def toSeconds : Long = throw new IllegalArgumentException("toSeconds not allowed on infinite Durations")
  }

  /**
   * Infinite duration: greater than any other and not equal to any other,
   * including itself.
   */
	object Inf extends Duration with Infinite {
	  override def toString = "Duration.Inf"
	  def >(other : Duration) = true
	  def >=(other : Duration) = true
	  def <(other : Duration) = false
	  def <=(other : Duration) = false
	  def unary_- : Duration = MinusInf
	}

  /**
   * Infinite negative duration: lesser than any other and not equal to any other,
   * including itself.
   */
  object MinusInf extends Duration with Infinite {
    override def toString = "Duration.MinusInf"
	  def >(other : Duration) = false
	  def >=(other : Duration) = false
	  def <(other : Duration) = true
	  def <=(other : Duration) = true
	  def unary_- : Duration = Inf
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
 * Duration duration = new Duration(100, MILLISECONDS);
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
trait Duration {
  def length : Long
  def unit : TimeUnit
  def toNanos : Long
  def toMicros : Long
  def toMillis : Long
  def toSeconds : Long
  def <(other : Duration) : Boolean
  def <=(other : Duration) : Boolean
  def >(other : Duration) : Boolean
  def >=(other : Duration) : Boolean
  def +(other : Duration) : Duration
  def -(other : Duration) : Duration
  def *(factor : Double) : Duration
  def /(factor : Double) : Duration
  def unary_- : Duration
  def finite_? : Boolean
}

class FiniteDuration(val length: Long, val unit: TimeUnit) extends Duration {
  def this(length: Long, unit: String) = this(length, Duration.timeUnit(unit))
  def this(length: Double, unit: TimeUnit) = {
    this(1, unit)
    this * length
  }

  def toNanos = unit.toNanos(length)
  def toMicros = unit.toMicros(length)
  def toMillis = unit.toMillis(length)
  def toSeconds = unit.toSeconds(length)

  override def toString = this match {
    case Duration(1, SECONDS) => "1 second"
    case Duration(x, SECONDS) => x+" seconds"
    case Duration(1, MILLISECONDS) => "1 millisecond"
    case Duration(x, MILLISECONDS) => x+" milliseconds"
    case Duration(1, MICROSECONDS) => "1 microsecond"
    case Duration(x, MICROSECONDS) => x+" microseconds"
    case Duration(1, NANOSECONDS) => "1 nanosecond"
    case Duration(x, NANOSECONDS) => x+" nanoseconds"
  }

  def <(other : Duration) = {
    if (other.finite_?) {
      toNanos < other.asInstanceOf[FiniteDuration].toNanos
    } else {
      other > this
    }
  }

  def <=(other : Duration) = {
    if (other.finite_?) {
      toNanos <= other.asInstanceOf[FiniteDuration].toNanos
    } else {
      other >= this
    }
  }

  def >(other : Duration) = {
    if (other.finite_?) {
      toNanos > other.asInstanceOf[FiniteDuration].toNanos
    } else {
      other < this
    }
  }

  def >=(other : Duration) = {
    if (other.finite_?) {
      toNanos >= other.asInstanceOf[FiniteDuration].toNanos
    } else {
      other <= this
    }
  }

  private def fromNanos(nanos : Long) : Duration = {
    if (nanos % 1000000000L == 0) {
      Duration(nanos / 1000000000L, SECONDS)
    } else if (nanos % 1000000L == 0) {
      Duration(nanos / 1000000L, MILLISECONDS)
    } else if (nanos % 1000L == 0) {
      Duration(nanos / 1000L, MICROSECONDS)
    } else {
      Duration(nanos, NANOSECONDS)
    }
  }

  private def fromNanos(nanos : Double) : Duration = fromNanos((nanos + 0.5).asInstanceOf[Long])

  def +(other : Duration) = {
    if (!other.finite_?) {
      other
    } else {
      val nanos = toNanos + other.asInstanceOf[FiniteDuration].toNanos
      fromNanos(nanos)
    }
  }

  def -(other : Duration) = {
    if (!other.finite_?) {
      other
    } else {
      val nanos = toNanos - other.asInstanceOf[FiniteDuration].toNanos
      fromNanos(nanos)
    }
  }

  def *(factor : Double) = fromNanos(long2double(toNanos) * factor)

  def /(factor : Double) = fromNanos(long2double(toNanos) / factor)

  def unary_- = Duration(-length, unit)

  def finite_? = true

  override def equals(other : Any) =
    other.isInstanceOf[FiniteDuration] &&
    toNanos == other.asInstanceOf[FiniteDuration].toNanos

  override def hashCode = toNanos.asInstanceOf[Int]
}

package object duration {
  implicit def intToDurationInt(n: Int) = new DurationInt(n)
  implicit def longToDurationLong(n: Long) = new DurationLong(n)
  implicit def doubleToDurationDouble(d: Double) = new DurationDouble(d)

  implicit def pairIntToDuration(p : (Int, TimeUnit)) = Duration(p._1, p._2)
  implicit def pairLongToDuration(p : (Long, TimeUnit)) = Duration(p._1, p._2)
  implicit def durationToPair(d : Duration) = (d.length, d.unit)

  implicit def intMult(i : Int) = new {
    def *(d : Duration) = d * i
  }
  implicit def longMult(l : Long) = new {
    def *(d : Duration) = d * l
  }
}

class DurationInt(n: Int) {
  def nanoseconds = Duration(n, NANOSECONDS)
  def nanos = Duration(n, NANOSECONDS)
  def nanosecond = Duration(n, NANOSECONDS)
  def nano = Duration(n, NANOSECONDS)

  def microseconds = Duration(n, MICROSECONDS)
  def micros =  Duration(n, MICROSECONDS)
  def microsecond = Duration(n, MICROSECONDS)
  def micro =  Duration(n, MICROSECONDS)

  def milliseconds =  Duration(n, MILLISECONDS)
  def millis = Duration(n, MILLISECONDS)
  def millisecond =  Duration(n, MILLISECONDS)
  def milli = Duration(n, MILLISECONDS)

  def seconds = Duration(n, SECONDS)
  def second = Duration(n, SECONDS)

  def minutes = Duration(60 * n, SECONDS)
  def minute = Duration(60 * n, SECONDS)

  def hours = Duration(3600 * n, SECONDS)
  def hour = Duration(3600 * n, SECONDS)
}

class DurationLong(n: Long) {
  def nanoseconds = Duration(n, NANOSECONDS)
  def nanos = Duration(n, NANOSECONDS)
  def nanosecond = Duration(n, NANOSECONDS)
  def nano = Duration(n, NANOSECONDS)

  def microseconds = Duration(n, MICROSECONDS)
  def micros =  Duration(n, MICROSECONDS)
  def microsecond = Duration(n, MICROSECONDS)
  def micro =  Duration(n, MICROSECONDS)

  def milliseconds =  Duration(n, MILLISECONDS)
  def millis = Duration(n, MILLISECONDS)
  def millisecond =  Duration(n, MILLISECONDS)
  def milli = Duration(n, MILLISECONDS)

  def seconds = Duration(n, SECONDS)
  def second = Duration(n, SECONDS)

  def minutes = Duration(60 * n, SECONDS)
  def minute = Duration(60 * n, SECONDS)

  def hours = Duration(3600 * n, SECONDS)
  def hour = Duration(3600 * n, SECONDS)
}

class DurationDouble(d: Double) {
  def nanoseconds = Duration(d, NANOSECONDS)
  def nanos = Duration(d, NANOSECONDS)
  def nanosecond = Duration(d, NANOSECONDS)
  def nano = Duration(d, NANOSECONDS)

  def microseconds = Duration(d, MICROSECONDS)
  def micros =  Duration(d, MICROSECONDS)
  def microsecond = Duration(d, MICROSECONDS)
  def micro =  Duration(d, MICROSECONDS)

  def milliseconds =  Duration(d, MILLISECONDS)
  def millis = Duration(d, MILLISECONDS)
  def millisecond =  Duration(d, MILLISECONDS)
  def milli = Duration(d, MILLISECONDS)

  def seconds = Duration(d, SECONDS)
  def second = Duration(d, SECONDS)

  def minutes = Duration(60 * d, SECONDS)
  def minute = Duration(60 * d, SECONDS)

  def hours = Duration(3600 * d, SECONDS)
  def hour = Duration(3600 * d, SECONDS)
}
