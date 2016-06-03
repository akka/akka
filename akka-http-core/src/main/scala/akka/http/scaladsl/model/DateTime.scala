/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.impl.util._

/**
 * Immutable, fast and efficient Date + Time implementation without any dependencies.
 * Does not support TimeZones, all DateTime values are always GMT based.
 * Note that this implementation discards milliseconds (i.e. rounds down to full seconds).
 */
final case class DateTime private (
  year:       Int, // the year
  month:      Int, // the month of the year. January is 1.
  day:        Int, // the day of the month. The first day is 1.
  hour:       Int, // the hour of the day. The first hour is 0.
  minute:     Int, // the minute of the hour. The first minute is 0.
  second:     Int, // the second of the minute. The first second is 0.
  weekday:    Int, // the day of the week. Sunday is 0.
  clicks:     Long, // milliseconds since January 1, 1970, 00:00:00 GMT
  isLeapYear: Boolean) extends akka.http.javadsl.model.DateTime with Ordered[DateTime] with Renderable {
  /**
   * The day of the week as a 3 letter abbreviation:
   * `Sun`, `Mon`, `Tue`, `Wed`, `Thu`, `Fri` or `Sat`
   */
  def weekdayStr: String = DateTime.weekday(weekday)

  /**
   * The month as a 3 letter abbreviation:
   * `Jan`, `Feb`, `Mar`, `Apr`, `May`, `Jun`, `Jul`, `Aug`, `Sep`, `Oct`, `Nov` or `Dec`
   */
  def monthStr: String = DateTime.month(month - 1)

  /**
   * Creates a new `DateTime` that represents the point in time the given number of ms later.
   */
  def +(millis: Long): DateTime = DateTime(clicks + millis)

  /**
   * Creates a new `DateTime` that represents the point in time the given number of ms earlier.
   */
  def -(millis: Long): DateTime = DateTime(clicks - millis)

  /**
   * Creates a new `DateTime` that represents the point in time the given number of ms earlier.
   */
  def minus(millis: Long): DateTime = this - millis

  /**
   * Creates a new `DateTime` that represents the point in time the given number of ms later.
   */
  def plus(millis: Long): DateTime = this + millis

  /**
   * `yyyy-mm-ddThh:mm:ss`
   */
  def render[R <: Rendering](r: R): r.type = renderIsoDateTimeString(r)

  /**
   * `yyyy-mm-ddThh:mm:ss`
   */
  override def toString = toIsoDateTimeString

  /**
   * `yyyy-mm-dd`
   */
  def renderIsoDate[R <: Rendering](r: R): r.type = put_##(put_##(r ~~ year ~~ '-', month) ~~ '-', day)

  /**
   * `yyyy-mm-dd`
   */
  def toIsoDateString = renderIsoDate(new StringRendering).get

  /**
   * `yyyy-mm-ddThh:mm:ss`
   */
  def renderIsoDateTimeString[R <: Rendering](r: R): r.type =
    put_##(put_##(put_##(renderIsoDate(r) ~~ 'T', hour) ~~ ':', minute) ~~ ':', second)

  /**
   * `yyyy-mm-ddThh:mm:ss`
   */
  def toIsoDateTimeString = renderIsoDateTimeString(new StringRendering).get

  /**
   * `yyyy-mm-dd hh:mm:ss`
   */
  def renderIsoLikeDateTimeString[R <: Rendering](r: R): r.type =
    put_##(put_##(put_##(renderIsoDate(r) ~~ ' ', hour) ~~ ':', minute) ~~ ':', second)

  /**
   * `yyyy-mm-dd hh:mm:ss`
   */
  def toIsoLikeDateTimeString = renderIsoLikeDateTimeString(new StringRendering).get

  /**
   * RFC1123 date string, e.g. `Sun, 06 Nov 1994 08:49:37 GMT`
   */
  def renderRfc1123DateTimeString[R <: Rendering](r: R): r.type =
    put_##(put_##(put_##(put_##(r ~~ weekdayStr ~~ ',' ~~ ' ', day) ~~ ' ' ~~ monthStr ~~ ' ' ~~ year ~~ ' ', hour) ~~ ':', minute) ~~ ':', second) ~~ " GMT"

  /**
   * RFC1123 date string, e.g. `Sun, 06 Nov 1994 08:49:37 GMT`
   */
  def toRfc1123DateTimeString = renderRfc1123DateTimeString(new StringRendering).get

  private def put_##[R <: Rendering](r: R, i: Int): r.type = r ~~ (i / 10 + '0').toChar ~~ (i % 10 + '0').toChar

  def compare(that: DateTime): Int = math.signum(clicks - that.clicks).toInt

  override def hashCode() = clicks.##

  override def equals(obj: Any) = obj match {
    case x: DateTime ⇒ x.clicks == clicks
    case _           ⇒ false
  }
}

object DateTime {
  private[this] val WEEKDAYS = Array("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
  private[this] val MONTHS = Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

  /**
   * Returns the three-letter string for the weekday with the given index. Sunday is zero.
   */
  def weekday(index: Int): String = WEEKDAYS(index)

  /**
   * Returns the three-letter string for the month with the given index. January is zero.
   */
  def month(index: Int): String = MONTHS(index)

  val MinValue = DateTime(1800, 1, 1)
  val MaxValue = DateTime(2199, 12, 31, 23, 59, 59)

  /**
   * Creates a new `DateTime` with the given properties.
   * Note that this implementation discards milliseconds (i.e. rounds down to full seconds).
   */
  def apply(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): DateTime = {
    require(1800 <= year && year <= 9999, "year must be >= 1800 and <= 9999")
    require(1 <= month && month <= 12, "month must be >= 1 and <= 12")
    require(1 <= day && day <= 31, "day must be >= 1 and <= 31")
    require(0 <= hour && hour <= 23, "hour must be >= 0 and <= 23")
    require(0 <= minute && minute <= 59, "minute_ must be >= 0 and <= 59")
    require(0 <= second && second <= 59, "second must be >= 0 and <= 59")

    // compute yearday from month/monthday
    val m = month - 1
    val m7 = m % 7
    var d = m7 * 30 + ((m7 + 1) >> 1) + day
    val isLeap = isLeapYear(year)
    if (m >= 7) d += 214
    if (d >= 61) d -= 1 // skip non-existent Feb 30
    if (!isLeap && (d >= 60)) d -= 1 // skip non-existent Feb 29

    // convert year/yearday to days since Jan 1, 1970, 00:00:00
    val y = year - 1
    val yd = y / 100
    d += y * 365 + (y >> 2) - yd + (yd >> 2)
    val dn = d - (1969 * 365 + 492 - 19 + 4)
    val c = (dn - 1) * 86400L + hour * 3600L + minute * 60L + second // seconds since Jan 1, 1970, 00:00:00

    new DateTime(year, month, day, hour, minute, second, weekday = d % 7, clicks = c * 1000, isLeapYear = isLeap)
  }

  /**
   * Creates a new `DateTime` from the number of milli seconds
   * since the start of "the epoch", namely January 1, 1970, 00:00:00 GMT.
   * Note that this implementation discards milliseconds (i.e. rounds down to full seconds).
   */
  def apply(clicks: Long): DateTime = {
    require(
      DateTime.MinValue.clicks <= clicks && clicks <= DateTime.MaxValue.clicks,
      "DateTime value must be >= " + DateTime.MinValue + " and <= " + DateTime.MaxValue)

    // based on a fast RFC1123 implementation (C) 2000 by Tim Kientzle <kientzle@acm.org>
    val c = clicks - clicks % 1000

    // compute day number, seconds since beginning of day
    var s = c
    if (s >= 0) s /= 1000 // seconds since 1 Jan 1970
    else s = (s - 999) / 1000 // floor(sec/1000)

    var dn = (s / 86400).toInt
    s %= 86400 // positive seconds since beginning of day
    if (s < 0) { s += 86400; dn -= 1 }
    dn += 1969 * 365 + 492 - 19 + 4 // days since "1 Jan, year 1"

    // convert days since 1 Jan, year 1 to year/yearday
    var y = 400 * (dn / 146097) + 1
    var d = dn % 146097
    if (d == 146096) { y += 399; d = 365 } // last year of 400 is long
    else {
      y += 100 * (d / 36524)
      d %= 36524
      y += (d / 1461) << 2
      d %= 1461
      if (d == 1460) { y += 3; d = 365 } // last year out of 4 is long
      else {
        y += d / 365
        d %= 365
      }
    }

    val isLeap = isLeapYear(y)

    // compute month/monthday from year/yearday
    if (!isLeap && (d >= 59)) d += 1 // skip non-existent Feb 29
    if (d >= 60) d += 1 // skip non-existent Feb 30
    val d214 = d % 214
    val d214_61 = d214 % 61
    var mon = ((d214 / 61) << 1) + d214_61 / 31
    if (d > 213) mon += 7
    d = d214_61 % 31 + 1

    // convert second to hour/min/sec
    var m = (s / 60).toInt
    s %= 60
    val h = m / 60
    m %= 60
    val w = (dn + 1) % 7 // day of week, 0==Sun

    new DateTime(year = y, month = mon + 1, day = d, hour = h, minute = m, second = s.toInt, weekday = w, clicks = c,
      isLeapYear = isLeap)
  }

  private def isLeapYear(year: Int): Boolean =
    ((year & 0x03) == 0) && {
      val q = year / 100
      val r = year % 100
      r != 0 || (q & 0x03) == 0
    }

  /**
   * Creates a new `DateTime` instance for the current point in time.
   * Note that this implementation discards milliseconds (i.e. rounds down to full seconds).
   */
  def now: DateTime = apply(System.currentTimeMillis)

  /**
   * Creates a new DateTime instance from the given String,
   * if it adheres to the format `yyyy-mm-ddThh:mm:ss[.SSSZ]`.
   * Note that this implementation discards milliseconds (i.e. rounds down to full seconds).
   */
  def fromIsoDateTimeString(string: String): Option[DateTime] = {
    def c(ix: Int) = string.charAt(ix)
    def isDigit(c: Char) = '0' <= c && c <= '9'
    def i(ix: Int) = {
      val x = c(ix)
      require(isDigit(x))
      x - '0'
    }
    def check(len: Int): Boolean =
      len match {
        case 19 ⇒ c(4) == '-' && c(7) == '-' && c(10) == 'T' && c(13) == ':' && c(16) == ':'
        case 24 ⇒ check(19) && c(19) == '.' && isDigit(c(20)) && isDigit(c(21)) && isDigit(c(22)) && c(23) == 'Z'
        case _  ⇒ false
      }
    def mul10(i: Int) = (i << 3) + (i << 1)
    if (check(string.length)) {
      try {
        val year = i(0) * 1000 + i(1) * 100 + mul10(i(2)) + i(3)
        val month = mul10(i(5)) + i(6)
        val day = mul10(i(8)) + i(9)
        val hour = mul10(i(11)) + i(12)
        val min = mul10(i(14)) + i(15)
        val sec = mul10(i(17)) + i(18)
        Some(DateTime(year, month, day, hour, min, sec))
      } catch { case _: IllegalArgumentException ⇒ None }
    } else None
  }
}