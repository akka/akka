/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.testkit._
import org.reactivestreams.Publisher

class FlowMergeSpec extends BaseTwoStreamsSetup {

  override type Outputs = Int

  override def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = TestSubscriber.probe[Outputs]()
    Source.fromPublisher(p1).merge(Source.fromPublisher(p2)).runWith(Sink.fromSubscriber(subscriber))
    subscriber
  }

  "A Merge for Flow " must {

    "work in the happy case" in assertAllStagesStopped {
      // Different input sizes (4 and 6)
      val source1 = Source(0 to 3)
      val source2 = Source(List[Int]())
      val source3 = Source(4 to 9)
      val probe = TestSubscriber.manualProbe[Int]()

      source1.merge(source2).merge(source3)
        .map(_ * 2).map(_ / 2).map(_ + 1).runWith(Sink.fromSubscriber(probe))

      val subscription = probe.expectSubscription()

      var collected = Set.empty[Int]
      for (_ ← 1 to 10) {
        subscription.request(1)
        collected += probe.expectNext()
      }

      collected should be(Set(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
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
      // This is nondeterministic, multiple scenarios can happen
      pending
    }

    "work with one delayed failed and one nonempty publisher" in {
      // This is nondeterministic, multiple scenarios can happen
      pending
    }

    "pass along early cancellation" in assertAllStagesStopped {
      val up1 = TestPublisher.manualProbe[Int]()
      val up2 = TestPublisher.manualProbe[Int]()
      val down = TestSubscriber.manualProbe[Int]()

      val (graphSubscriber1, graphSubscriber2) = Source.asSubscriber[Int]
        .mergeMat(Source.asSubscriber[Int])((_, _)).toMat(Sink.fromSubscriber(down))(Keep.left).run

      val downstream = down.expectSubscription()
      downstream.cancel()
      up1.subscribe(graphSubscriber1)
      up2.subscribe(graphSubscriber2)
      up1.expectSubscription().expectCancellation()
      up2.expectSubscription().expectCancellation()
    }
  }
}
