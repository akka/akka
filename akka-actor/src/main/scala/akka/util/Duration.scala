/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import java.util.concurrent.TimeUnit

object Duration {
  def apply(length: Long, unit: TimeUnit) : Duration = new FiniteDuration(length, unit)
  def apply(length: Long, unit: String) : Duration = new FiniteDuration(length, timeUnit(unit))

  def timeUnit(unit: String) = unit.toLowerCase match {
    case "nanoseconds" | "nanos" | "nanosecond" | "nano" => TimeUnit.NANOSECONDS
    case "microseconds" | "micros" |  "microsecond" | "micro" => TimeUnit.MICROSECONDS
    case "milliseconds" | "millis" | "millisecond" | "milli" => TimeUnit.MILLISECONDS
    case _ => TimeUnit.SECONDS
  }

  trait Infinite {
    this : Duration =>

	  override def equals(other : Any) = false

	  def +(other : Duration) : Duration = this
	  def -(other : Duration) : Duration = this
	  def *(other : Double) : Duration = this
	  def /(other : Double) : Duration = this
	
	  def finite_? = false
	
	  def length : Long = error("length not allowed on infinite Durations")
	  def unit : TimeUnit = error("unit not allowed on infinite Durations")
	  def toNanos : Long = error("toNanos not allowed on infinite Durations")
	  def toMicros : Long = error("toMicros not allowed on infinite Durations")
	  def toMillis : Long = error("toMillis not allowed on infinite Durations")
	  def toSeconds : Long = error("toSeconds not allowed on infinite Durations")
  }

	object Inf extends Duration with Infinite {
	  override def toString = "Duration.Inf"
	  def >(other : Duration) = false
	  def >=(other : Duration) = false
	  def <(other : Duration) = true
	  def <=(other : Duration) = true
	  def unary_- : Duration = MinusInf
	}

  object MinusInf extends Duration with Infinite {
    override def toString = "Duration.MinusInf"
	  def >(other : Duration) = true
	  def >=(other : Duration) = true
	  def <(other : Duration) = false
	  def <=(other : Duration) = false
	  def unary_- : Duration = MinusInf
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
  def toNanos = unit.toNanos(length)
  def toMicros = unit.toMicros(length)
  def toMillis = unit.toMillis(length)
  def toSeconds = unit.toSeconds(length)
  override def toString = "Duration(" + length + ", " + unit + ")"

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
      Duration(nanos / 1000000000L, TimeUnit.SECONDS)
    } else if (nanos % 1000000L == 0) {
      Duration(nanos / 1000000L, TimeUnit.MILLISECONDS)
    } else if (nanos % 1000L == 0) {
      Duration(nanos / 1000L, TimeUnit.MICROSECONDS)
    } else {
      Duration(nanos, TimeUnit.NANOSECONDS)
    }
  }

  private def fromNanos(nanos : Double) : Duration = fromNanos(nanos.asInstanceOf[Long])

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

object Inf extends Duration {
  override def toString = "Duration.Inf"
  override def equals(other : Any) = false

  def >(other : Duration) = true
  def >=(other : Duration) = true
  def <(other : Duration) = false
  def <=(other : Duration) = false

  def +(other : Duration) : Duration = this
  def -(other : Duration) : Duration = this
  def *(other : Double) : Duration = this
  def /(other : Double) : Duration = this

  def unary_- : Duration = MinusInf

  def finite_? = false

  def length : Long = error("length not allowed on Inf")
  def unit : TimeUnit = error("unit not allowed on Inf")
  def toNanos : Long = error("toNanos not allowed on Inf")
  def toMicros : Long = error("toMicros not allowed on Inf")
  def toMillis : Long = error("toMillis not allowed on Inf")
  def toSeconds : Long = error("toSeconds not allowed on Inf")
}

object MinusInf extends Duration {
  override def toString = "Duration.MinusInf"
  override def equals(other : Any) = false

  def >(other : Duration) = false
  def >=(other : Duration) = false
  def <(other : Duration) = true
  def <=(other : Duration) = true

  def +(other : Duration) : Duration = this
  def -(other : Duration) : Duration = this
  def *(other : Double) : Duration = this
  def /(other : Double) : Duration = this

  def unary_- : Duration = Inf

  def finite_? = false

  def length : Long = error("length not allowed on MinusInf")
  def unit : TimeUnit = error("unit not allowed on MinusInf")
  def toNanos : Long = error("toNanos not allowed on MinusInf")
  def toMicros : Long = error("toMicros not allowed on MinusInf")
  def toMillis : Long = error("toMillis not allowed on MinusInf")
  def toSeconds : Long = error("toSeconds not allowed on MinusInf")
}

package object duration {
  implicit def intToDurationInt(n: Int) = new DurationInt(n)
  implicit def longToDurationLong(n: Long) = new DurationLong(n)
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

  def minutes = Duration(60 * n, TimeUnit.SECONDS)
  def minute = Duration(60 * n, TimeUnit.SECONDS)

  def hours = Duration(3600 * n, TimeUnit.SECONDS)
  def hour = Duration(3600 * n, TimeUnit.SECONDS)
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

  def minutes = Duration(60 * n, TimeUnit.SECONDS)
  def minute = Duration(60 * n, TimeUnit.SECONDS)

  def hours = Duration(3600 * n, TimeUnit.SECONDS)
  def hour = Duration(3600 * n, TimeUnit.SECONDS)
}
