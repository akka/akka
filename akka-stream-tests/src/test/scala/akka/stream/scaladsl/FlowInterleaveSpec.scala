/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.testkit._
import org.reactivestreams.Publisher

class FlowInterleaveSpec extends BaseTwoStreamsSetup {

  override type Outputs = Int

  override def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = TestSubscriber.probe[Outputs]()
    Source(p1).interleave(Source(p2), 2).runWith(Sink(subscriber))
    subscriber
  }

  "An Interleave for Flow " must {

    "work in the happy case" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()
      Source(0 to 3).interleave(Source(List[Int]()), 2).interleave(Source(4 to 9), 2).runWith(Sink(probe))

      val subscription = probe.expectSubscription()

      var collected = Set.empty[Int]
      for (_ ← 1 to 10) {
        subscription.request(1)
        collected += probe.expectNext()
      }

      collected should be(Set(0, 1, 4, 5, 2, 3, 6, 7, 8, 9))
      probe.expectComplete()
    }

    "work when bucket is not equal elements in stream" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()

      Source(0 to 2).interleave(Source(3 to 5), 2).runWith(Sink(probe))
      probe.expectSubscription().request(10)
      probe.expectNext(0, 1, 3, 4, 2, 5)
      probe.expectComplete()
    }

    "work with bucket = 1" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()

      Source(0 to 2).interleave(Source(3 to 5), 1).runWith(Sink(probe))
      probe.expectSubscription().request(10)
      probe.expectNext(0, 3, 1, 4, 2, 5)
      probe.expectComplete()
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher(1 to 4))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(4)
      (1 to 4).foreach(subscriber1.expectNext)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), completedPublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(4)
      (1 to 4).foreach(subscriber2.expectNext)
      subscriber2.expectComplete()
    }

    "work with one delayed completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher(1 to 4))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(4)
      (1 to 4).foreach(subscriber1.expectNext)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToCompletePublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(4)
      (1 to 4).foreach(subscriber2.expectNext)
      subscriber2.expectComplete()
    }

    "work with one immediately failed and one nonempty publisher" in {
      val subscriber1 = setup(failedPublisher, nonemptyPublisher(1 to 4))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(4)
      subscriber1.expectError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), failedPublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(4)
      subscriber2.expectError(TestException)

    }

    "work with one delayed failed and one nonempty publisher" in {
      // This is nondeterministic, multiple scenarios can happen
      pending
    }

    "pass along early cancellation" in assertAllStagesStopped {
      val up1 = TestPublisher.manualProbe[Int]()
      val up2 = TestPublisher.manualProbe[Int]()
      val down = TestSubscriber.manualProbe[Int]()

      val (graphSubscriber1, graphSubscriber2) = Source.subscriber[Int]
        .interleaveMat(Source.subscriber[Int], 2)((_, _)).toMat(Sink(down))(Keep.left).run

      val downstream = down.expectSubscription()
      downstream.cancel()
      up1.subscribe(graphSubscriber1)
      up2.subscribe(graphSubscriber2)
      up1.expectSubscription().expectCancellation()
      up2.expectSubscription().expectCancellation()
    }
  }

}
