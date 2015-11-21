/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.{ AkkaSpec, TestPublisher, TestSubscriber }
import akka.stream.testkit.Utils._

import scala.concurrent.duration._

class FlowDelaySpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A Delay" must {

    "deliver element after time passed" in {
      Source(1 to 3).delay(300.millis).runWith(TestSink.probe[Int])
        .request(3)
        .expectNoMsg(100.millis)
        .expectNext(1)
        .expectNoMsg(100.millis)
        .expectNext(2)
        .expectNoMsg(100.millis)
        .expectNext(3)
        .expectComplete()
    }

    "deliver buffered elements onComplete before the timeout" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      Source(1 to 3).delay(300.millis).to(Sink(c)).run()
      val cSub = c.expectSubscription()
      c.expectNoMsg(200.millis)
      cSub.request(100)
      (1 to 3) foreach { n ⇒ c.expectNext(n) }
      c.expectComplete()
      c.expectNoMsg(200.millis)
    }
  }
}
