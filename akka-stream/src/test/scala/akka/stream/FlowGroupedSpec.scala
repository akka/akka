/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.collection.immutable
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

class FlowGroupedSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  "A Grouped" must {

    "group evenly" in {
      def script = Script((1 to 20) map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) -> Seq(immutable.Seq(x, y, z)) }: _*)
      (1 to 30) foreach (_ ⇒ runScript(script, settings)(_.grouped(3)))
    }

    "group with rest" in {
      def script = Script(((1 to 20).map { _ ⇒ val x, y, z = random.nextInt(); Seq(x, y, z) -> Seq(immutable.Seq(x, y, z)) }
        :+ { val x = random.nextInt(); Seq(x) -> Seq(immutable.Seq(x)) }): _*)
      (1 to 30) foreach (_ ⇒ runScript(script, settings)(_.grouped(3)))
    }

  }

}