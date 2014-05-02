/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics.report

import java.util.concurrent.TimeUnit._
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

object PrettyDuration {

  implicit class PrettyPrintableDuration(val d: Duration) extends AnyVal {

    /** Selects most apropriate TimeUnit for given duration and formats it accordingly */
    def pretty: String = pretty(includeNanos = false)

    /** Selects most apropriate TimeUnit for given duration and formats it accordingly */
    def pretty(includeNanos: Boolean = false): String = {
      val nanos = d.toNanos

      val unit = chooseUnit(nanos)
      val value = nanos.toDouble / NANOSECONDS.convert(1, unit)

      "%.4g %s%s".format(value, abbreviate(unit), if (includeNanos) s" ($nanos ns)" else "")
    }

    def chooseUnit(nanos: Long): TimeUnit =
      if (DAYS.convert(nanos, NANOSECONDS) > 0) DAYS
      else if (HOURS.convert(nanos, NANOSECONDS) > 0) HOURS
      else if (MINUTES.convert(nanos, NANOSECONDS) > 0) MINUTES
      else if (SECONDS.convert(nanos, NANOSECONDS) > 0) SECONDS
      else if (MILLISECONDS.convert(nanos, NANOSECONDS) > 0) MILLISECONDS
      else if (MICROSECONDS.convert(nanos, NANOSECONDS) > 0) MICROSECONDS
      else NANOSECONDS

    def abbreviate(unit: TimeUnit): String = unit match {
      case NANOSECONDS  ⇒ "ns"
      case MICROSECONDS ⇒ "\u03bcs" // μs
      case MILLISECONDS ⇒ "ms"
      case SECONDS      ⇒ "s"
      case MINUTES      ⇒ "min"
      case HOURS        ⇒ "h"
      case DAYS         ⇒ "d"
    }
  }

}
