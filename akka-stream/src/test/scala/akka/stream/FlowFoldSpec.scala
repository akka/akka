/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

class FlowFoldSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  "A Fold" must {

    "fold" in {
      def script = Script((1 to 50).toSeq -> Seq(25 * 51))
      (1 to 50) foreach (_ ⇒ runScript(script, settings)(_.fold(0)(_ + _)))
    }

  }

}