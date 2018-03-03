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
          result should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 12, 12))
      }

      "work for larger functions" in {
        val result = LineNumbers(twoline)
        if (isScala212)
          // because how scala 2.12 does the same as Java Lambdas
          result should ===(NoSourceInfo)
        else
          result should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 14, 16))
      }

      "work for partial functions" in {
        LineNumbers(partial) should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 19, 21))
      }

      "work for `def`" in {
        LineNumbers(method("foo")) should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 24, 26))
      }

    }

    "writing Java" must {
      val l = new LineNumberSpecCodeForJava

      "work for small functions" in {
        // because how java Lambdas are implemented/designed
        LineNumbers(l.f1()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 19, 19))
      }

      "work for larger functions" in {
        // because how java Lambdas are implemented/designed
        LineNumbers(l.f2()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 24, 25))
      }

      "work for anonymous classes" in {
        LineNumbers(l.f3()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 30, 35))
      }

    }

  }

}
