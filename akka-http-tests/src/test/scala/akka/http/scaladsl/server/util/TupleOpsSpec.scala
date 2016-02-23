/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.util

import org.scalatest.{ Matchers, WordSpec }

class TupleOpsSpec extends WordSpec with Matchers {
  import TupleOps._

  "The TupleOps" should {

    "support folding over tuples using a binary poly-function" in {
      object Funky extends BinaryPolyFunc {
        implicit def step1 = at[Double, Int](_ + _)
        implicit def step2 = at[Double, Symbol]((d, s) ⇒ (d + s.name.tail.toInt).toByte)
        implicit def step3 = at[Byte, String]((byte, s) ⇒ byte + s.toLong)
      }
      (1, 'X2, "3").foldLeft(0.0)(Funky) shouldEqual 6L
    }

    "support joining tuples" in {
      (1, 'X2, "3") join (()) shouldEqual ((1, 'X2, "3"))
      () join ((1, 'X2, "3")) shouldEqual ((1, 'X2, "3"))
      (1, 'X2, "3") join ((4.0, 5L)) shouldEqual ((1, 'X2, "3", 4.0, 5L))
    }
  }
}