/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.impl.OnNext
import akka.stream.impl.OnComplete
import akka.stream.impl.RequestMore

class FlowTakeSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  muteDeadLetters(classOf[OnNext], OnComplete.getClass, classOf[RequestMore])()

  "A Take" must {

    "take" in {
      def script(d: Int) = Script((1 to 50) map { n ⇒ Seq(n) -> (if (n > d) Nil else Seq(n)) }: _*)
      (1 to 50) foreach { _ ⇒
        val d = Math.min(Math.max(random.nextInt(-10, 60), 0), 50)
        runScript(script(d), settings)(_.take(d))
      }
    }

  }

}