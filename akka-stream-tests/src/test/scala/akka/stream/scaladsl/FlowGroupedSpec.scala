/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.ThreadLocalRandom.{ current => random }

import akka.stream.testkit.ScriptedTest
import akka.stream.testkit.StreamSpec

import scala.collection.immutable

class FlowGroupedSpec extends StreamSpec("""
    akka.stream.materializer.initial-input-buffer-size = 2
  """) with ScriptedTest {

  "A Grouped" must {

    def randomSeq(n: Int) = immutable.Seq.fill(n)(random.nextInt())
    def randomTest(n: Int) = { val s = randomSeq(n); s -> immutable.Seq(s) }

    "group evenly" in {
      val testLen = random.nextInt(1, 16)
      def script =
        Script(TestConfig.RandomTestRange.map { _ =>
          randomTest(testLen)
        }: _*)
      TestConfig.RandomTestRange.foreach(_ => runScript(script)(_.grouped(testLen)))
    }

    "group with rest" in {
      val testLen = random.nextInt(1, 16)
      def script =
        Script(TestConfig.RandomTestRange.map { _ =>
          randomTest(testLen)
        } :+ randomTest(1): _*)
      TestConfig.RandomTestRange.foreach(_ => runScript(script)(_.grouped(testLen)))
    }

  }

}
