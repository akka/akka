/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.testkit._
import org.reactivestreams.Publisher

class FlowInterleaveSpec extends BaseTwoStreamsSetup {

  override type Outputs = Int

  override def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = TestSubscriber.probe[Outputs]()
    Source.fromPublisher(p1).interleave(Source.fromPublisher(p2), 2).runWith(Sink.fromSubscriber(subscriber))
    subscriber
  }

  "An Interleave for Flow " must {

    "work in the happy case" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()
      Source(0 to 3).interleave(Source(4 to 6), 2).interleave(Source(7 to 11), 3).runWith(Sink.fromSubscriber(probe))

      val subscription = probe.expectSubscription()

      var collected = Seq.empty[Int]
      for (_ ← 1 to 12) {
        subscription.request(1)
        collected :+= probe.expectNext()
      }

      collected should be(Seq(0, 1, 4, 7, 8, 9, 5, 2, 3, 10, 11, 6))
      probe.expectComplete()
    }

    "work when segmentSize is not equal elements in stream" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()

      Source(0 to 2).interleave(Source(3 to 5), 2).runWith(Sink.fromSubscriber(probe))
      probe.expectSubscription().request(10)
      probe.expectNext(0, 1, 3, 4, 2, 5)
      probe.expectComplete()
    }

    "work with segmentSize = 1" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()

      Source(0 to 2).interleave(Source(3 to 5), 1).runWith(Sink.fromSubscriber(probe))
      probe.expectSubscription().request(10)
      probe.expectNext(0, 3, 1, 4, 2, 5)
      probe.expectComplete()
    }

    "not work with segmentSize = 0" in assertAllStagesStopped {
      an[IllegalArgumentException] mustBe thrownBy(Source(0 to 2).interleave(Source(3 to 5), 0).runWith(Sink.head))
    }

    "not work when segmentSize > than stream elements" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()
      Source(0 to 2).interleave(Source(3 to 15), 10).runWith(Sink.fromSubscriber(probe))
      probe.expectSubscription().request(25)
      (0 to 15).foreach(probe.expectNext)
      probe.expectComplete()

      val probe2 = TestSubscriber.manualProbe[Int]()
      Source(1 to 20).interleave(Source(21 to 25), 10).runWith(Sink.fromSubscriber(probe2))
      probe2.expectSubscription().request(100)
      (1 to 10).foreach(probe2.expectNext)
      (21 to 25).foreach(probe2.expectNext)
      (11 to 20).foreach(probe2.expectNext)
      probe2.expectComplete()
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
      subscriber2.expectNextOrError(1, TestException).isLeft ||
        subscriber2.expectNextOrError(2, TestException).isLeft ||
        { subscriber2.expectError(TestException); true }
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
        .interleaveMat(Source.asSubscriber[Int], 2)((_, _)).toMat(Sink.fromSubscriber(down))(Keep.left).run

      val downstream = down.expectSubscription()
      downstream.cancel()
      up1.subscribe(graphSubscriber1)
      up2.subscribe(graphSubscriber2)
      up1.expectSubscription().expectCancellation()
      up2.expectSubscription().expectCancellation()
    }
  }

}
