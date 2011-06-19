/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import duration._

class DurationSpec extends WordSpec with MustMatchers {

  "Duration" must {

    "form a one-dimensional vector field" in {
      val zero = 0.seconds
      val one = 1.second
      val two = one + one
      val three = 3 * one
      (0 * one) must be(zero)
      (2 * one) must be(two)
      (three - two) must be(one)
      (three / 3) must be(one)
      (two / one) must be(2)
      (one + zero) must be(one)
      (one / 1000000) must be(1.micro)
    }

    "respect correct treatment of infinities" in {
      val one = 1.second
      val inf = Duration.Inf
      val minf = Duration.MinusInf
      (-inf) must be(minf)
      intercept[IllegalArgumentException] { minf + inf }
      intercept[IllegalArgumentException] { inf - inf }
      intercept[IllegalArgumentException] { inf + minf }
      intercept[IllegalArgumentException] { minf - minf }
      (inf + inf) must be(inf)
      (inf - minf) must be(inf)
      (minf - inf) must be(minf)
      (minf + minf) must be(minf)
    }

  }

}
