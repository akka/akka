/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

class FlowProduceToConsumerSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings())

  "A Flow with toProducer" must {

    "produce elements to the consumer" in {
      val c = StreamTestKit.consumerProbe[Int]
      Flow(List(1, 2, 3)).produceTo(materializer, c)
      val s = c.expectSubscription()
      s.requestMore(3)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }
  }

}