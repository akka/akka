/**
  * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
  */
package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream.testkit.Utils.assertAllStagesStopped
import akka.stream.testkit.scaladsl.{ TestSource, TestSink }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }

class FlowDeduplicateSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A Deduplicate" must {

    "remove consecutive duplicates" in {
      val input = List(1, 1, 1, 2, 2, 1, 1, 3)
      val probe = TestSubscriber.manualProbe[Int]()
      Source(input).deduplicate.runWith(Sink.fromSubscriber(probe))

      val subscription = probe.expectSubscription()
      subscription.request(input.size)

      probe.expectNext(1, 2, 1, 3)
      probe.expectComplete()
    }
  }
}