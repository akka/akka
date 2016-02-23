/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import akka.testkit.AkkaSpec
import LineNumbers._

class LineNumberSpec extends AkkaSpec {

  "LineNumbers" when {

    "writing Scala" must {
      import LineNumberSpecCodeForScala._

      "work for small functions" in {
        LineNumbers(oneline) should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 12, 12))
      }

      "work for larger functions" in {
        LineNumbers(twoline) should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 14, 16))
      }

      "work for partial functions" in {
        LineNumbers(partial) should ===(SourceFileLines("LineNumberSpecCodeForScala.scala", 19, 21))
      }

    }

    "writing Java" must {
      val l = new LineNumberSpecCodeForJava

      // FIXME uncomment when compiling with '-source 1.8'
      //      "work for small functions" in {
      //        LineNumbers(l.f1()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 20, 20))
      //      }

      //      "work for larger functions" in {
      //        LineNumbers(l.f2()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 25, 26))
      //      }

      "work for anonymous classes" in {
        LineNumbers(l.f3()) should ===(SourceFileLines("LineNumberSpecCodeForJava.java", 31, 35))
      }

    }

  }

}
