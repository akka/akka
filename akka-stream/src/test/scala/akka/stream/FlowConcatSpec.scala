/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.impl.{ IteratorProducer, ActorBasedProcessorGenerator }
import akka.stream.testkit.StreamTestKit
import akka.testkit.AkkaSpec
import akka.stream.scaladsl.Flow

class FlowConcatSpec extends AkkaSpec {

  val gen = new ActorBasedProcessorGenerator(GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2), system)

  "Concat" must {

    "work in the happy case" in {
      val source0 = Flow(List.empty[Int].iterator).toProducer(gen)
      val source1 = Flow((1 to 4).iterator).toProducer(gen)
      val source2 = Flow((5 to 10).iterator).toProducer(gen)
      val p = Flow(source0).concat(source1).concat(source2).toProducer(gen)

      val probe = StreamTestKit.consumerProbe[Int]
      p.produceTo(probe)
      val subscription = probe.expectSubscription()

      for (i ← 1 to 10) {
        subscription.requestMore(1)
        probe.expectNext(i)
      }

      probe.expectComplete()
    }

  }
}
