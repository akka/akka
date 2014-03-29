/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

class StreamFoldSpec extends AkkaSpec with ScriptedTest {

  val genSettings = GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  "A Fold" must {

    "fold" in {
      def script = Script((1 to 50).toSeq -> Seq(25 * 51))
      (1 to 50) foreach (_ ⇒ runScript(script, genSettings)(_.fold(0)(_ + _)))
    }

  }

}