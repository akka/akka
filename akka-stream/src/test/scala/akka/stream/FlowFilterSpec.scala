/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.{ StreamTestKit, ScriptedTest }
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.scaladsl.Flow
import akka.stream.impl.ActorBasedFlowMaterializer

class FlowFilterSpec extends AkkaSpec with ScriptedTest {

  val settings = MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16,
    dispatcher = "akka.test.stream-dispatcher")

  "A Filter" must {

    "filter" in {
      def script = Script((1 to 50) map { _ ⇒ val x = random.nextInt(); Seq(x) -> (if ((x & 1) == 0) Seq(x) else Seq()) }: _*)
      (1 to 50) foreach (_ ⇒ runScript(script, settings)(_.filter(_ % 2 == 0)))
    }

    "not blow up with high request counts" in {
      implicit val materializer = FlowMaterializer(MaterializerSettings(
        initialInputBufferSize = 1,
        maximumInputBufferSize = 1,
        initialFanOutBufferSize = 1,
        maxFanOutBufferSize = 1,
        dispatcher = "akka.test.stream-dispatcher"))

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