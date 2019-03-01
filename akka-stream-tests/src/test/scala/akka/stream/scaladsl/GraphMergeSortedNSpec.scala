/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream._
import akka.stream.testkit.Utils._
import akka.stream.testkit._

import scala.collection.immutable
import scala.concurrent.duration._
import akka.stream.testkit.scaladsl.StreamTestKit._

class GraphMergeSortedNSpec extends TwoStreamsSetup {
  import GraphDSL.Implicits._

  override type Outputs = Int

  override def fixture(b: GraphDSL.Builder[_]): Fixture = new Fixture(b) {
    val mergeSortedN = b.add(MergeSortedN[Int](2))

    override def left: Inlet[Int] = mergeSortedN.in(0)
    override def right: Inlet[Int] = mergeSortedN.in(1)
    override def out: Outlet[Int] = mergeSortedN.out
  }

  "GraphMergeSortedN" must {

    "work in the happy case" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
        val mergeSortedN = b.add(MergeSortedN[Int](2))

        Source(1 to 4) ~> mergeSortedN.in(0)
        Source(2 to 5) ~> mergeSortedN.in(1)

        mergeSortedN.out ~> Sink.fromSubscriber(probe)

        ClosedShape
      }).run()

      val subscription = probe.expectSubscription()

      subscription.request(4)
      probe.expectNext(1, 2, 2, 3)

      subscription.request(2)
      probe.expectNext(3, 4)
      subscription.request(4)
      probe.expectNext(4, 5)

      probe.expectComplete()
    }

    "not complete if one side is available but other completed" in {
      val upstream1 = TestPublisher.probe[Int]()
      val upstream2 = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create(Sink.fromSubscriber(downstream)) { implicit b ⇒ out ⇒
        val mergeSortedN = b.add(MergeSortedN[Int](2))

        Source.fromPublisher(upstream1) ~> mergeSortedN.in(0)
        Source.fromPublisher(upstream2) ~> mergeSortedN.in(1)
        mergeSortedN.out ~> out

        ClosedShape
      }).run()

      upstream1.sendNext(1)
      upstream1.sendNext(2)
      upstream2.sendNext(2)
      upstream1.sendComplete()

      downstream.requestNext(1)
      downstream.requestNext(2)
      downstream.requestNext(2)
      downstream.expectNoMessage(200.millis)
    }

    "complete even if no pending demand" in {
      val upstream1 = TestPublisher.probe[Int]()
      val upstream2 = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create(Sink.fromSubscriber(downstream)) { implicit b ⇒ out ⇒
        val mergeSortedN = b.add(MergeSortedN[Int](2))

        Source.fromPublisher(upstream1) ~> mergeSortedN.in(0)
        Source.fromPublisher(upstream2) ~> mergeSortedN.in(1)
        mergeSortedN.out ~> out

        ClosedShape
      }).run()

      downstream.request(2)

      upstream1.sendNext(1)
      upstream2.sendNext(2)

      upstream2.sendComplete()
      upstream1.sendComplete()
      downstream.expectNext(1, 2)
      downstream.expectComplete()
    }

    "complete if both sides complete before requested with elements pending" in {
      val upstream1 = TestPublisher.probe[Int]()
      val upstream2 = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      RunnableGraph.fromGraph(GraphDSL.create(Sink.fromSubscriber(downstream)) { implicit b ⇒ out ⇒
        val mergeSortedN = b.add(MergeSortedN[Int](2))

        Source.fromPublisher(upstream1) ~> mergeSortedN.in(0)
        Source.fromPublisher(upstream2) ~> mergeSortedN.in(1)
        mergeSortedN.out ~> out

        ClosedShape
      }).run()

      upstream1.sendNext(1)
      upstream2.sendNext(2)

      upstream1.sendComplete()
      upstream2.sendComplete()

      downstream.request(2)
      downstream.expectNext(1, 2)
      downstream.expectComplete()
    }
    commonTests()

    "work with one immediately completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher(1 to 1))
      subscriber1.request(10)
      subscriber1.expectNext(1)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 1), completedPublisher)
      subscriber2.request(10)
      subscriber2.requestNext(1)
      subscriber2.expectComplete()
    }

    "work with one delayed completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher(1 to 4))

      subscriber1.requestNext(1)
      subscriber1.requestNext(2)
      subscriber1.requestNext(3)
      subscriber1.requestNext(4)
      subscriber1.onComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToCompletePublisher)
      subscriber2.requestNext(1)
      subscriber2.requestNext(2)
      subscriber2.requestNext(3)
      subscriber2.requestNext(4)
      subscriber2.expectComplete()
    }

    "work with one immediately failed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(failedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), failedPublisher)
      subscriber2.expectSubscriptionAndError(TestException)
    }

    "work with one delayed failed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(soonToFailPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToFailPublisher)
      val subscription2 = subscriber2.expectSubscriptionAndError(TestException)
    }

    "never emit if a source does not emit" in {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher(1 to 2))

      subscriber1.expectSubscription()
      subscriber1.expectNoMessage(100 millis)
    }

  }

}
