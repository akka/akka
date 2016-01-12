/**
  * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
  */
package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream.testkit.Utils.assertAllStagesStopped
import akka.stream.testkit.scaladsl.{ TestSource, TestSink }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }

class FlowDedupeSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A Dedupe" must {

    "remove consecutive duplicates" in {
      val input = List(1, 1, 1, 2, 2, 1, 1, 3)
      val probe = TestSubscriber.manualProbe[Int]()
      Source(input).dedupe.runWith(Sink.fromSubscriber(probe))

      val subscription = probe.expectSubscription()
      subscription.request(Long.MaxValue)

      probe.expectNext(1)
      probe.expectNext(2)
      probe.expectNext(1)
      probe.expectNext(3)
      probe.expectComplete()
    }
  }
}
