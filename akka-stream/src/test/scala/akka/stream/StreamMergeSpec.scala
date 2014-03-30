/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.testkit.AkkaSpec
import org.reactivestreams.api.Producer
import akka.stream.impl.{ IteratorProducer, ActorBasedProcessorGenerator }
import akka.stream.{ Stream, GeneratorSettings }

class StreamMergeSpec extends AkkaSpec {

  import system.dispatcher

  val gen = new ActorBasedProcessorGenerator(GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2), system)

  "merge" must {

    "work in the happy case" in {
      // Different input sizes (4 and 6)
      val source1 = Stream((1 to 4).iterator).toProducer(gen)
      val source2 = Stream((5 to 10).iterator).toProducer(gen)
      val source3 = Stream(List.empty[Int].iterator).toProducer(gen)
      val p = Stream(source1).merge(source2).merge(source3).toProducer(gen)

      val probe = StreamTestKit.consumerProbe[Int]
      p.produceTo(probe)
      val subscription = probe.expectSubscription()

      var collected = Set.empty[Int]
      for (_ ← 1 to 10) {
        subscription.requestMore(1)
        collected += probe.expectNext()
      }

      collected should be(Set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
      probe.expectComplete()
    }

  }

}
