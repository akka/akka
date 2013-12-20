/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import java.util.TimeZone
import scala.util.Random
import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.matchers.{ Matcher, MatchResult }

class DateTimeSpec extends WordSpec with Matchers {

  val GMT = TimeZone.getTimeZone("GMT")
  val specificClicks = DateTime(2011, 7, 12, 14, 8, 12).clicks
  val startClicks = DateTime(1800, 1, 1, 0, 0, 0).clicks
  val maxClickDelta = DateTime(2199, 12, 31, 23, 59, 59).clicks - startClicks
  val random = new Random()
  val httpDateTimes = Stream.continually {
    DateTime(startClicks + math.abs(random.nextLong()) % maxClickDelta)
  }

  "DateTime.toRfc1123DateTimeString" should {
    "properly print a known date" in {
      DateTime(specificClicks).toRfc1123DateTimeString === "Tue, 12 Jul 2011 14:08:12 GMT"
      DateTime(2011, 7, 12, 14, 8, 12).toRfc1123DateTimeString === "Tue, 12 Jul 2011 14:08:12 GMT"
    }
    "behave exactly as a corresponding formatting via SimpleDateFormat" in {
      val Rfc1123Format = {
        val fmt = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
        fmt.setTimeZone(GMT)
        fmt
      }
      def rfc1123Format(dt: DateTime) = Rfc1123Format.format(new java.util.Date(dt.clicks))
      val matchSimpleDateFormat: Matcher[DateTime] = Matcher { dt: DateTime ⇒
        MatchResult(
          dt.toRfc1123DateTimeString == rfc1123Format(dt),
          dt.toRfc1123DateTimeString + " != " + rfc1123Format(dt),
          dt.toRfc1123DateTimeString + " == " + rfc1123Format(dt))
      }
      all(httpDateTimes.take(10000)) should matchSimpleDateFormat
    }
  }

  "DateTime.toIsoDateTimeString" should {
    "properly print a known date" in {
      DateTime(specificClicks).toIsoDateTimeString === "2011-07-12T14:08:12"
    }
  }

  "DateTime.fromIsoDateTimeString" should {
    "properly parse a legal string" in {
      DateTime.fromIsoDateTimeString("2011-07-12T14:08:12") shouldBe Some(DateTime(specificClicks))
    }
    "fail on an illegal string" when {
      "example 1" in { DateTime.fromIsoDateTimeString("2011-07-12T14:08:12x") shouldBe None }
      "example 2" in { DateTime.fromIsoDateTimeString("2011-07-12T14:08_12") shouldBe None }
      "example 3" in { DateTime.fromIsoDateTimeString("201A-07-12T14:08:12") shouldBe None }
      "example 4" in { DateTime.fromIsoDateTimeString("2011-13-12T14:08:12") shouldBe None }
    }
  }

  "The two DateTime implementations" should {
    "allow for transparent round-trip conversions" in {
      def roundTrip(dt: DateTime) = DateTime(dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second)
      val roundTripOk: Matcher[DateTime] = Matcher { dt: DateTime ⇒
        MatchResult(
          { val rt = roundTrip(dt); dt == rt && dt.weekday == rt.weekday },
          dt.toRfc1123DateTimeString + " != " + roundTrip(dt).toRfc1123DateTimeString,
          dt.toRfc1123DateTimeString + " == " + roundTrip(dt).toRfc1123DateTimeString)
      }
      all(httpDateTimes.take(10000)) should roundTripOk
    }
    "properly represent DateTime.MinValue" in {
      DateTime.MinValue.toString === "1800-01-01T00:00:00"
      DateTime(DateTime.MinValue.clicks).toString === "1800-01-01T00:00:00"
    }
    "properly represent DateTime.MaxValue" in {
      DateTime.MaxValue.toString === "2199-12-31T23:59:59"
      DateTime(DateTime.MaxValue.clicks).toString === "2199-12-31T23:59:59"
    }
  }
}
