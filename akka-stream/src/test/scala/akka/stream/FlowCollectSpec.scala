/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.{ StreamTestKit, ScriptedTest }
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

class FlowCollectSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings()

  "A Collect" must {

    "collect" in {
      val range = 1 to 50
      def script = Script(range map { _ ⇒
        val x = random.nextInt(0, 10000)
        Seq(x) -> (if ((x & 1) == 0) Seq((x * x).toString) else Seq.empty[String])
      }: _*)
      range foreach (_ ⇒ runScript(script, settings)(_.collect { case x if x % 2 == 0 ⇒ (x * x).toString }))
    }

  }

}