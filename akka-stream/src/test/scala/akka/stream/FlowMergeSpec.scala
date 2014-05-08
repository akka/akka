/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import org.reactivestreams.api.Producer
import akka.stream.scaladsl.Flow

class FlowMergeSpec extends TwoStreamsSetup {

  type Outputs = Int
  override def operationUnderTest(in1: Flow[Int], in2: Producer[Int]) = in1.merge(in2)

  "merge" must {

    "work in the happy case" in {
      // Different input sizes (4 and 6)
      val source1 = Flow((1 to 4).iterator).toProducer(materializer)
      val source2 = Flow((5 to 10).iterator).toProducer(materializer)
      val source3 = Flow(List.empty[Int].iterator).toProducer(materializer)
      val p = Flow(source1).merge(source2).merge(source3).toProducer(materializer)

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

    commonTests()

    "work with one immediately completed and one nonempty producer" in {
      val consumer1 = setup(completedPublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = consumer1.expectSubscription()
      subscription1.requestMore(4)
      consumer1.expectNext(1)
      consumer1.expectNext(2)
      consumer1.expectNext(3)
      consumer1.expectNext(4)
      consumer1.expectComplete()

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), completedPublisher)
      val subscription2 = consumer2.expectSubscription()
      subscription2.requestMore(4)
      consumer2.expectNext(1)
      consumer2.expectNext(2)
      consumer2.expectNext(3)
      consumer2.expectNext(4)
      consumer2.expectComplete()
    }

    "work with one delayed completed and one nonempty producer" in {
      val consumer1 = setup(soonToCompletePublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = consumer1.expectSubscription()
      subscription1.requestMore(4)
      consumer1.expectNext(1)
      consumer1.expectNext(2)
      consumer1.expectNext(3)
      consumer1.expectNext(4)
      consumer1.expectComplete()

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), soonToCompletePublisher)
      val subscription2 = consumer2.expectSubscription()
      subscription2.requestMore(4)
      consumer2.expectNext(1)
      consumer2.expectNext(2)
      consumer2.expectNext(3)
      consumer2.expectNext(4)
      consumer2.expectComplete()
    }

    "work with one immediately failed and one nonempty producer" in {
      // This is nondeterministic, multiple scenarios can happen
      pending
    }

    "work with one delayed failed and one nonempty producer" in {
      // This is nondeterministic, multiple scenarios can happen
      pending
    }

  }

}
