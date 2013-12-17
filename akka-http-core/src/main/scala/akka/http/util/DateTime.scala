package akka.http.util

import akka.http.rendering._

/**
 * Immutable, fast and efficient Date + Time implementation without any dependencies.
 * Does not support TimeZones, all DateTime values are always GMT based.
 */
case class DateTime private (year: Int, // the year
                             month: Int, // the month of the year. January is 1.
                             day: Int, // the day of the month. The first day is 1.
                             hour: Int, // the hour of the day. The first hour is 0.
                             minute: Int, // the minute of the hour. The first minute is 0.
                             second: Int, // the second of the minute. The first second is 0.
                             weekday: Int, // the day of the week. Sunday is 0.
                             clicks: Long, // milliseconds since January 1, 1970, 00:00:00 GMT
                             isLeapYear: Boolean) extends Ordered[DateTime] with Renderable {
  /**
   * The day of the week as a 3 letter abbreviation:
   * `Sun`, `Mon`, `Tue`, `Wed`, `Thu`, `Fri` or `Sat`
   */
  def weekdayStr: String = DateTime.WEEKDAYS(weekday)

  /**
   * The day of the month as a 3 letter abbreviation:
   * `Jan`, `Feb`, `Mar`, `Apr`, `May`, `Jun`, `Jul`, `Aug`, `Sep`, `Oct`, `Nov` or `Dec`
   */
  def monthStr: String = DateTime.MONTHS(month - 1)

  /**
   * Creates a new `DateTime` that represents the point in time the given number of ms later.
   */
  def +(millis: Long): DateTime = DateTime(clicks + millis)

  /**
   * Creates a new `DateTime` that represents the point in time the given number of ms earlier.
   */
  def -(millis: Long): DateTime = DateTime(clicks - millis)

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
  val WEEKDAYS = Array("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
  val MONTHS = Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
  val MinValue = DateTime(1800, 1, 1)
  val MaxValue = DateTime(2199, 12, 31, 23, 59, 59)

  /**
   * Creates a new `DateTime` with the given properties.
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
    var d = (m % 7) * 30 + (m % 7 + 1) / 2 + day
    val isLeap = ((year % 4 == 0) && !(year % 100 == 0)) || (year % 400 == 0)
    if (m >= 7) d += 214
    if (d >= 61) d -= 1 // skip non-existent Feb 30
    if (!isLeap && (d >= 60)) d -= 1 // skip non-existent Feb 29

    // convert year/yearday to days since Jan 1, 1970, 00:00:00
    val y = year - 1
    d += y * 365 + y / 4 - y / 100 + y / 400
    val dn = d - (1969 * 365 + 492 - 19 + 4)
    val c = (dn - 1) * 86400L + hour * 3600L + minute * 60L + second // seconds since Jan 1, 1970, 00:00:00

    new DateTime(year, month, day, hour, minute, second, weekday = d % 7, clicks = c * 1000, isLeapYear = isLeap)
  }

  /**
   * Creates a new `DateTime` from the number of milli seconds
   * since the start of "the epoch", namely January 1, 1970, 00:00:00 GMT.
   */
  def apply(clicks: Long): DateTime = {
    require(DateTime.MinValue.clicks <= clicks && clicks <= DateTime.MaxValue.clicks,
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
    var y = 400 * (dn / 146097).toInt + 1
    var d = dn % 146097
    if (d == 146096) { y += 399; d = 365 } // last year of 400 is long
    else {
      y += 100 * (d / 36524)
      d %= 36524
      y += 4 * (d / 1461)
      d %= 1461
      if (d == 1460) { y += 3; d = 365 } // last year out of 4 is long
      else {
        y += d / 365
        d %= 365
      }
    }

    val isLeap = ((y % 4 == 0) && !(y % 100 == 0)) || (y % 400 == 0)

    // compute month/monthday from year/yearday
    if (!isLeap && (d >= 59)) d += 1 // skip non-existent Feb 29
    if (d >= 60) d += 1 // skip non-existent Feb 30
    var mon = ((d % 214) / 61) * 2 + ((d % 214) % 61) / 31
    if (d > 213) mon += 7
    d = ((d % 214) % 61) % 31 + 1

    // convert second to hour/min/sec
    var m = (s / 60).toInt
    val h = m / 60
    m %= 60
    s %= 60
    val w = (dn + 1) % 7 // day of week, 0==Sun

    new DateTime(year = y, month = mon + 1, day = d, hour = h, minute = m, second = s.toInt, weekday = w, clicks = c,
      isLeapYear = isLeap)
  }

  /**
   * Creates a new `DateTime` instance for the current point in time.
   */
  def now: DateTime = apply(System.currentTimeMillis)

  /**
   * Creates a new DateTime instance from the given String,
   * if it adheres to the format `yyyy-mm-ddThh:mm:ss`.
   */
  def fromIsoDateTimeString(string: String): Option[DateTime] = {
    def c(ix: Int) = string.charAt(ix)
    def i(ix: Int) = {
      val x = c(ix)
      require('0' <= x && x <= '9')
      x - '0'
    }
    if (string.length == 19 && c(4) == '-' && c(7) == '-' && c(10) == 'T' && c(13) == ':' && c(16) == ':') {
      try {
        val year = i(0) * 1000 + i(1) * 100 + i(2) * 10 + i(3)
        val month = i(5) * 10 + i(6)
        val day = i(8) * 10 + i(9)
        val hour = i(11) * 10 + i(12)
        val min = i(14) * 10 + i(15)
        val sec = i(17) * 10 + i(18)
        Some(DateTime(year, month, day, hour, min, sec))
      } catch { case _: IllegalArgumentException ⇒ None }
    } else None
  }
}
