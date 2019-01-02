/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.ActorAttributes._
import akka.stream.Supervision._
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.TestSink

import java.util.concurrent.ThreadLocalRandom.{ current ⇒ random }

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.{ StreamSpec, ScriptedTest }

class FlowCollectSpec extends StreamSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
  implicit val materializer = ActorMaterializer(settings)

  "A Collect" must {

    "collect" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒
        val x = random.nextInt(0, 10000)
        Seq(x) → (if ((x & 1) == 0) Seq((x * x).toString) else Seq.empty[String])
      }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.collect { case x if x % 2 == 0 ⇒ (x * x).toString }))
    }

    "restart when Collect throws" in {
      val pf: PartialFunction[Int, Int] =
        { case x: Int ⇒ if (x == 2) throw TE("") else x }
      Source(1 to 3).collect(pf).withAttributes(supervisionStrategy(restartingDecider))
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectNext(1)
        .request(1)
        .expectNext(3)
        .request(1)
        .expectComplete()
    }

  }

}
