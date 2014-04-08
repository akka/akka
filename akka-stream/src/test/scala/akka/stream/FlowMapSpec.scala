/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

class StreamMapSpec extends AkkaSpec with ScriptedTest {

  val genSettings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  "A Map" must {

    "map" in {
      def script = Script((1 to 50) map { _ ⇒ val x = random.nextInt(); Seq(x) -> Seq(x.toString) }: _*)
      (1 to 50) foreach (_ ⇒ runScript(script, genSettings)(_.map(_.toString)))
    }

  }

}