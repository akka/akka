/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.util

import org.scalatest.{ Matchers, FreeSpec }

class TupleOpsSpec extends FreeSpec with Matchers {
  import TupleOps._

  "The TupleOps should" - {

    "support folding over tuples using a binary poly-function" - {

      "example 1" in {
        object Funky extends Poly2 {
          implicit def step1 = at[Double, Int](_ + _)
          implicit def step2 = at[Double, Symbol]((d, s) ⇒ (d + s.name.tail.toInt).toByte)
          implicit def step3 = at[Byte, String]((byte, s) ⇒ byte + s.toLong)
        }
        (1, 'X2, "3").foldLeft(0.0)(Funky) shouldEqual 6L
      }
    }
  }
}