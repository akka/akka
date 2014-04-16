/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

class FlowForeachSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  "A Foreach" must {

    "foreach" in {
      var count = 0
      def script = {
        count = 0
        Script((1 to 50).toSeq -> Seq(()))
      }
      (1 to 50) foreach { _ ⇒
        runScript(script, settings)(_.foreach(x ⇒ count += x))
        count should be(25 * 51)
      }
    }

  }

}