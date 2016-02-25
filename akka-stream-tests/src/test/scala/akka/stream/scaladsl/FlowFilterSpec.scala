/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit._
import akka.testkit.AkkaSpec

class FlowFilterSpec extends AkkaSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  "A Filter" must {

    "filter" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒ val x = random.nextInt(); Seq(x) -> (if ((x & 1) == 0) Seq(x) else Seq()) }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.filter(_ % 2 == 0)))
    }

    "not blow up with high request counts" in {
      val settings = ActorMaterializerSettings(system)
        .withInputBuffer(initialSize = 1, maxSize = 1)
      implicit val materializer = ActorMaterializer(settings)

      val probe = TestSubscriber.manualProbe[Int]()
      Source(List.fill(1000)(0) ::: List(1)).filter(_ != 0).runWith(Sink.fromSubscriber(probe))

      val subscription = probe.expectSubscription()
      for (_ ← 1 to 10000) {
        subscription.request(Int.MaxValue)
      }

      probe.expectNext(1)
      probe.expectComplete()
    }

  }

  "A FilterNot" must {
    "filter based on inverted predicate" in {
      def script = Script(TestConfig.RandomTestRange map
        { _ ⇒
          val x = random.nextInt()
          Seq(x) -> (if ((x & 1) == 1) Seq(x) else Seq())
        }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.filterNot(_ % 2 == 0)))
    }
  }

}
