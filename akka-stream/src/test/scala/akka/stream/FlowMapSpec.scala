/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.{ StreamTestKit, ScriptedTest }
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.scaladsl.Flow
import akka.stream.impl.ActorBasedFlowMaterializer

class FlowMapSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16)

  val gen = FlowMaterializer(settings)

  "A Map" must {

    "map" in {
      def script = Script((1 to 50) map { _ ⇒ val x = random.nextInt(); Seq(x) -> Seq(x.toString) }: _*)
      (1 to 50) foreach (_ ⇒ runScript(script, settings)(_.map(_.toString)))
    }

    "not blow up with high request counts" in {
      val probe = StreamTestKit.consumerProbe[Int]
      Flow(List(1).iterator).
        map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).
        toProducer(gen).produceTo(probe)

      val subscription = probe.expectSubscription()
      for (_ ← 1 to 10000) {
        subscription.requestMore(Int.MaxValue)
      }

      probe.expectNext(6)
      probe.expectComplete()

    }

  }

}