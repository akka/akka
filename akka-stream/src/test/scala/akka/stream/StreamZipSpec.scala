/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.impl.{ IteratorProducer, ActorBasedProcessorGenerator }
import akka.stream.testkit.StreamTestKit
import akka.testkit.AkkaSpec

class StreamZipSpec extends AkkaSpec {

  val gen = new ActorBasedProcessorGenerator(GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2), system)

  "Zip" must {

    "work in the happy case" in {
      // Different input sizes (4 and 6)
      val source1 = Stream((1 to 4).iterator).toProducer(gen)
      val source2 = Stream(List("A", "B", "C", "D", "E", "F").iterator).toProducer(gen)
      val p = Stream(source1).zip(source2).toProducer(gen)

      val probe = StreamTestKit.consumerProbe[(Int, String)]
      p.produceTo(probe)
      val subscription = probe.expectSubscription()

      subscription.requestMore(2)
      probe.expectNext((1, "A"))
      probe.expectNext((2, "B"))

      subscription.requestMore(1)
      probe.expectNext((3, "C"))
      subscription.requestMore(1)
      probe.expectNext((4, "D"))

      probe.expectComplete()
    }

  }

}
