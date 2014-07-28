/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.StreamTestKit
import org.reactivestreams.Publisher

class FlowZipSpec extends TwoStreamsSetup {

  type Outputs = (Int, Int)
  override def operationUnderTest(in1: Flow[Int], in2: Publisher[Int]) = in1.zip(in2)

  "Zip" must {

    "work in the happy case" in {
      // Different input sizes (4 and 6)
      val source1 = Flow((1 to 4).iterator).toPublisher(materializer)
      val source2 = Flow(List("A", "B", "C", "D", "E", "F").iterator).toPublisher(materializer)
      val p = Flow(source1).zip(source2).toPublisher(materializer)

      val probe = StreamTestKit.SubscriberProbe[(Int, String)]()
      p.subscribe(probe)
      val subscription = probe.expectSubscription()

      subscription.request(2)
      probe.expectNext((1, "A"))
      probe.expectNext((2, "B"))

      subscription.request(1)
      probe.expectNext((3, "C"))
      subscription.request(1)
      probe.expectNext((4, "D"))

      probe.expectComplete()
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher((1 to 4).iterator))
      subscriber1.expectCompletedOrSubscriptionFollowedByComplete()

      val subscriber2 = setup(nonemptyPublisher((1 to 4).iterator), completedPublisher)
      subscriber2.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with one delayed completed and one nonempty publisher" in {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher((1 to 4).iterator))
      subscriber1.expectCompletedOrSubscriptionFollowedByComplete()

      val subscriber2 = setup(nonemptyPublisher((1 to 4).iterator), soonToCompletePublisher)
      subscriber2.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with one immediately failed and one nonempty publisher" in {
      val subscriber1 = setup(failedPublisher, nonemptyPublisher((1 to 4).iterator))
      subscriber1.expectErrorOrSubscriptionFollowedByError(TestException)

      val subscriber2 = setup(nonemptyPublisher((1 to 4).iterator), failedPublisher)
      subscriber2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with one delayed failed and one nonempty publisher" in {
      val subscriber1 = setup(soonToFailPublisher, nonemptyPublisher((1 to 4).iterator))
      subscriber1.expectErrorOrSubscriptionFollowedByError(TestException)

      val subscriber2 = setup(nonemptyPublisher((1 to 4).iterator), soonToFailPublisher)
      val subscription2 = subscriber2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

  }

}
