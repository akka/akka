/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

class FlowProduceToSubscriberSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))

  "A Flow with toPublisher" must {

    "produce elements to the subscriber" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      Flow(List(1, 2, 3)).produceTo(materializer, c)
      val s = c.expectSubscription()
      s.request(3)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }
  }

}