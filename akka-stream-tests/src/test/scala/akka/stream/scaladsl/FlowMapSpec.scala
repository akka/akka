/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.testkit.ScriptedTest

class FlowMapSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "A Map" must {

    "map" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒ val x = random.nextInt(); Seq(x) -> Seq(x.toString) }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.map(_.toString)))
    }

    "not blow up with high request counts" in {
      val probe = StreamTestKit.SubscriberProbe[Int]()
      Source(List(1)).
        map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).
        runWith(Sink.publisher).subscribe(probe)

      val subscription = probe.expectSubscription()
      for (_ ← 1 to 10000) {
        subscription.request(Int.MaxValue)
      }

      probe.expectNext(6)
      probe.expectComplete()

    }

  }

}
