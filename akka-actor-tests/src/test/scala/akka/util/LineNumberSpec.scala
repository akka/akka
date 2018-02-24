/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.testkit.AkkaSpec
import LineNumbers._

import scala.util.Properties

class LineNumberSpec extends AkkaSpec {

  private val isScala212 = Properties.versionNumberString.startsWith("2.12")

  "LineNumbers" when {

    "writing Scala" must {
      import LineNumberSpecCodeForScala._

      "work for small functions" in {
        val result = LineNumbers(oneline)

        if (isScala212)
          // because how scala 2.12 does the same as Java Lambdas
          result should ===(NoSourceInfo)
        else
          result should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 13, 13))
      }

      "work for larger functions" in {
        val result = LineNumbers(twoline)
        if (isScala212)
          // because how scala 2.12 does the same as Java Lambdas
          result should ===(NoSourceInfo)
        else
          result should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 15, 17))
      }

      "work for partial functions" in {
        LineNumbers(partial) should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 20, 22))
      }

      "work for `def`" in {
        LineNumbers(method("foo")) should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 25, 27))
      }

    }

    "writing Java" must {
      val l = new LineNumberSpecCodeForJava

      "work for small functions" in {
        // because how java Lambdas are implemented/designed
        LineNumbers(l.f1()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 20, 20))
      }

      "work for larger functions" in {
        // because how java Lambdas are implemented/designed
        LineNumbers(l.f2()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 25, 26))
      }

      "work for anonymous classes" in {
        LineNumbers(l.f3()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 31, 36))
      }

    }

  }

}
