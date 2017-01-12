/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import java.util.concurrent.ThreadLocalRandom.{ current ⇒ random }

import akka.stream.ActorMaterializerSettings
import akka.stream.testkit.{ StreamSpec, ScriptedTest }

class FlowGroupedSpec extends StreamSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  "A Grouped" must {

    def randomSeq(n: Int) = immutable.Seq.fill(n)(random.nextInt())
    def randomTest(n: Int) = { val s = randomSeq(n); s → immutable.Seq(s) }

    "group evenly" in {
      val testLen = random.nextInt(1, 16)
      def script = Script(TestConfig.RandomTestRange map { _ ⇒ randomTest(testLen) }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.grouped(testLen)))
    }

    "group with rest" in {
      val testLen = random.nextInt(1, 16)
      def script = Script(TestConfig.RandomTestRange.map { _ ⇒ randomTest(testLen) } :+ randomTest(1): _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.grouped(testLen)))
    }

  }

}
