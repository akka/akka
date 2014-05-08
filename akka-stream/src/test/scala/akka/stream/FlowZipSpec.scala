/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import akka.stream.scaladsl.Flow
import org.reactivestreams.api.Producer
import akka.stream.testkit.OnSubscribe
import akka.stream.testkit.OnError

class FlowZipSpec extends TwoStreamsSetup {

  type Outputs = (Int, Int)
  override def operationUnderTest(in1: Flow[Int], in2: Producer[Int]) = in1.zip(in2)

  "Zip" must {

    "work in the happy case" in {
      // Different input sizes (4 and 6)
      val source1 = Flow((1 to 4).iterator).toProducer(materializer)
      val source2 = Flow(List("A", "B", "C", "D", "E", "F").iterator).toProducer(materializer)
      val p = Flow(source1).zip(source2).toProducer(materializer)

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

    commonTests()

    "work with one immediately completed and one nonempty producer" in {
      val consumer1 = setup(completedPublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = consumer1.expectSubscription()
      subscription1.requestMore(4)
      consumer1.expectComplete()

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), completedPublisher)
      val subscription2 = consumer2.expectSubscription()
      subscription2.requestMore(4)
      consumer2.expectComplete()
    }

    "work with one delayed completed and one nonempty producer" in {
      val consumer1 = setup(soonToCompletePublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = consumer1.expectSubscription()
      subscription1.requestMore(4)
      consumer1.expectComplete()

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), soonToCompletePublisher)
      val subscription2 = consumer2.expectSubscription()
      subscription2.requestMore(4)
      consumer2.expectComplete()
    }

    "work with one immediately failed and one nonempty producer" in {
      val consumer1 = setup(failedPublisher, nonemptyPublisher((1 to 4).iterator))
      consumer1.expectErrorOrSubscriptionFollowedByError(TestException)

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), failedPublisher)
      val subscription2 = consumer2.expectSubscription()
      subscription2.requestMore(4)
      consumer2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with one delayed failed and one nonempty producer" in {
      val consumer1 = setup(soonToFailPublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = consumer1.expectSubscription()
      consumer1.expectErrorOrSubscriptionFollowedByError(TestException)

      val consumer2 = setup(nonemptyPublisher((1 to 4).iterator), soonToFailPublisher)
      val subscription2 = consumer2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

  }

}
