/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics.reporter

import org.scalatest.{ Matchers, FlatSpec }
import java.util.Locale

class PrettyDurationSpec extends FlatSpec with Matchers {

  behavior of "PrettyDuration"

  import concurrent.duration._
  import PrettyDuration._

  val cases: Seq[(Duration, (Float, Int, String))] =
    9.nanos -> ((9.000f, 3, "ns")) ::
      95.nanos -> ((95.00f, 2, "ns")) ::
      999.nanos -> ((999.0f, 1, "ns")) ::
      1000.nanos -> ((1.000f, 3, "μs")) ::
      9500.nanos -> ((9.500f, 3, "μs")) ::
      9500.micros -> ((9.500f, 3, "ms")) ::
      9500.millis -> ((9.500f, 3, "s")) ::
      95.seconds -> ((1.583f, 3, "min")) ::
      95.minutes -> ((1.583f, 3, "h")) ::
      95.hours -> ((3.958f, 3, "d")) ::
      Nil

  cases foreach {
    case (d, (expectedValue, precision, unit)) ⇒
      val prettyString = s"%.${precision}f $unit" formatLocal (Locale.getDefault, expectedValue)
      it should s"print $d seconds as $prettyString" in {
        d.pretty should equal(prettyString)
      }
  }

  it should "work with infinity" in {
    Duration.Inf.pretty should include("infinity")
  }

  it should "work with -infinity" in {
    Duration.MinusInf.pretty should include("minus infinity")
  }
}
