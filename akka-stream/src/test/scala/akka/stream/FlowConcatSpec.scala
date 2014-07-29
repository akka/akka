/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.StreamTestKit
import akka.stream.scaladsl.Flow
import org.reactivestreams.Publisher
import scala.concurrent.Promise

class FlowConcatSpec extends TwoStreamsSetup {

  type Outputs = Int
  override def operationUnderTest(in1: Flow[Int], in2: Publisher[Int]) = in1.concat(in2)

  "Concat" must {

    "work in the happy case" in {
      val source0 = Flow(List.empty[Int].iterator).toPublisher(materializer)
      val source1 = Flow((1 to 4).iterator).toPublisher(materializer)
      val source2 = Flow((5 to 10).iterator).toPublisher(materializer)
      val p = Flow(source0).concat(source1).concat(source2).toPublisher(materializer)

      val probe = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(probe)
      val subscription = probe.expectSubscription()

      for (i ← 1 to 10) {
        subscription.request(1)
        probe.expectNext(i)
      }

      probe.expectComplete()
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(5)
      subscriber1.expectNext(1)
      subscriber1.expectNext(2)
      subscriber1.expectNext(3)
      subscriber1.expectNext(4)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher((1 to 4).iterator), completedPublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(5)
      subscriber2.expectNext(1)
      subscriber2.expectNext(2)
      subscriber2.expectNext(3)
      subscriber2.expectNext(4)
      subscriber2.expectComplete()
    }

    "work with one delayed completed and one nonempty publisher" in {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher((1 to 4).iterator))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(5)
      subscriber1.expectNext(1)
      subscriber1.expectNext(2)
      subscriber1.expectNext(3)
      subscriber1.expectNext(4)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher((1 to 4).iterator), soonToCompletePublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(5)
      subscriber2.expectNext(1)
      subscriber2.expectNext(2)
      subscriber2.expectNext(3)
      subscriber2.expectNext(4)
      subscriber2.expectComplete()
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
      subscriber2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "correctly handle async errors in secondary upstream" in {
      val promise = Promise[Int]()
      val flow = Flow(List(1, 2, 3)).concat(Flow(promise.future).toPublisher(materializer))
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      flow.produceTo(materializer, subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(4)
      subscriber.expectNext(1)
      subscriber.expectNext(2)
      subscriber.expectNext(3)
      promise.failure(TestException)
      subscriber.expectError(TestException)
    }
  }
}
