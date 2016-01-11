/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

class SubscriberSinkSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val materializer = FlowMaterializer(settings)

  "A Flow with SubscriberSink" must {

    "publish elements to the subscriber" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      Source(List(1, 2, 3)).connect(Sink(c)).run()
      val s = c.expectSubscription()
      s.request(3)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }
  }

}
