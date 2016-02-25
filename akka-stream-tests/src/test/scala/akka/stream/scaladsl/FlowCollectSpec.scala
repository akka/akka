/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

import akka.stream.ActorMaterializerSettings
import akka.testkit.AkkaSpec
import akka.stream.testkit.ScriptedTest

class FlowCollectSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)

  "A Collect" must {

    "collect" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒
        val x = random.nextInt(0, 10000)
        Seq(x) -> (if ((x & 1) == 0) Seq((x * x).toString) else Seq.empty[String])
      }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.collect { case x if x % 2 == 0 ⇒ (x * x).toString }))
    }

  }

}
