/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.{ AkkaSpec, ScriptedTest, StreamTestKit }

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

class FlowFilterSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  "A Filter" must {

    "filter" in {
      def script = Script((1 to 50) map { _ ⇒ val x = random.nextInt(); Seq(x) -> (if ((x & 1) == 0) Seq(x) else Seq()) }: _*)
      (1 to 50) foreach (_ ⇒ runScript(script, settings)(_.filter(_ % 2 == 0)))
    }

    "not blow up with high request counts" in {
      val settings = MaterializerSettings(system)
        .withInputBuffer(initialSize = 1, maxSize = 1)
        .withFanOutBuffer(initialSize = 1, maxSize = 1)
      implicit val materializer = FlowMaterializer(settings)

      val probe = StreamTestKit.SubscriberProbe[Int]()
      Flow(Iterator.fill(1000)(0) ++ List(1)).filter(_ != 0).
        toPublisher().subscribe(probe)

      val subscription = probe.expectSubscription()
      for (_ ← 1 to 10000) {
        subscription.request(Int.MaxValue)
      }

      probe.expectNext(1)
      probe.expectComplete()

    }

  }

}