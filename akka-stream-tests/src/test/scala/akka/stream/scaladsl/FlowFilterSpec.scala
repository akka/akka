/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }

import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.testkit.ScriptedTest

class FlowFilterSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  "A Filter" must {

    "filter" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒ val x = random.nextInt(); Seq(x) -> (if ((x & 1) == 0) Seq(x) else Seq()) }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.filter(_ % 2 == 0)))
    }

    "not blow up with high request counts" in {
      val settings = ActorFlowMaterializerSettings(system)
        .withInputBuffer(initialSize = 1, maxSize = 1)
      implicit val materializer = ActorFlowMaterializer(settings)

      val probe = StreamTestKit.SubscriberProbe[Int]()
      Source(List.fill(1000)(0) ::: List(1)).filter(_ != 0).runWith(Sink(probe))

      val subscription = probe.expectSubscription()
      for (_ ← 1 to 10000) {
        subscription.request(Int.MaxValue)
      }

      probe.expectNext(1)
      probe.expectComplete()

    }

  }

}
