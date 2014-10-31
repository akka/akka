/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.collection.immutable
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest

class FlowGroupedSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  "A Grouped" must {

    "group evenly" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) -> Seq(immutable.Seq(x, y, z)) }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.grouped(3)))
    }

    "group with rest" in {
      def script = Script((TestConfig.RandomTestRange.map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) -> Seq(immutable.Seq(x, y, z)) }
        :+ { val x = random.nextInt(); Seq(x) -> Seq(immutable.Seq(x)) }): _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.grouped(3)))
    }

  }

}