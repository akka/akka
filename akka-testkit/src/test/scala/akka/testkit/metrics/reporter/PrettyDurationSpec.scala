/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics.reporter

import org.scalatest.{ Matchers, FlatSpec }

class PrettyDurationSpec extends FlatSpec with Matchers {

  behavior of "PrettyDuration"

  import concurrent.duration._
  import PrettyDuration._

  val cases =
    95.nanos -> "95 ns" ::
      9500.nanos -> "9.5 μs" ::
      9500.micros -> "9.5 ms" ::
      9500.millis -> "9.5 s" ::
      95.seconds -> "1.6 min" ::
      95.minutes -> "1.6 h" ::
      95.hours -> "4.0 d" ::
      Nil

  cases foreach {
    case (d, prettyString) ⇒
      it should s"print $d seconds as $prettyString" in {
        d.pretty should equal(prettyString)
      }
  }
}
