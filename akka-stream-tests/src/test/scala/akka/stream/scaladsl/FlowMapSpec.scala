/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.ThreadLocalRandom.{ current ⇒ random }

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit._

class FlowMapSpec extends StreamSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A Map" must {

    "map" in {
      def script = Script(TestConfig.RandomTestRange map { _ ⇒ val x = random.nextInt(); Seq(x) → Seq(x.toString) }: _*)
      TestConfig.RandomTestRange foreach (_ ⇒ runScript(script, settings)(_.map(_.toString)))
    }

    "not blow up with high request counts" in {
      val probe = TestSubscriber.manualProbe[Int]()
      Source(List(1)).
        map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).
        runWith(Sink.asPublisher(false)).subscribe(probe)

      val subscription = probe.expectSubscription()
      for (_ ← 1 to 10000) {
        subscription.request(Int.MaxValue)
      }

      probe.expectNext(6)
      probe.expectComplete()

    }

  }

}
