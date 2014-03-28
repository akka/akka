/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest

class StreamMapConcatSpec extends AkkaSpec with ScriptedTest {

  val genSettings = GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  "A MapConcat" must {

    "map and concat" in {
      val script = Script(
        Seq(1) -> Seq(1),
        Seq(2) -> Seq(2, 2),
        Seq(3) -> Seq(3, 3, 3),
        Seq(2) -> Seq(2, 2),
        Seq(1) -> Seq(1))
      (1 to 100) foreach (_ ⇒ runScript(script, genSettings)(_.mapConcat(x ⇒ (1 to x) map (_ ⇒ x))))
    }

  }

}