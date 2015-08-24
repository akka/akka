/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.stream.testkit.Utils._

class GraphConcatSpec extends TwoStreamsSetup {

  override type Outputs = Int

  override def fixture(b: FlowGraph.Builder[_]): Fixture = new Fixture(b) {
    val concat = b add Concat[Outputs]()

    override def left: Inlet[Outputs] = concat.in(0)
    override def right: Inlet[Outputs] = concat.in(1)
    override def out: Outlet[Outputs] = concat.out

  }

  "Concat" must {
    import FlowGraph.Implicits._

    "work in the happy case" in assertAllStagesStopped {
      val probe = TestSubscriber.manualProbe[Int]()

      FlowGraph.closed() { implicit b ⇒

        val concat1 = b add Concat[Int]()
        val concat2 = b add Concat[Int]()

        Source(List.empty[Int]) ~> concat1.in(0)
        Source(1 to 4) ~> concat1.in(1)

        concat1.out ~> concat2.in(0)
        Source(5 to 10) ~> concat2.in(1)

        concat2.out ~> Sink(probe)
      }.run()

      val subscription = probe.expectSubscription()

      for (i ← 1 to 10) {
        subscription.request(1)
        probe.expectNext(i)
      }

      probe.expectComplete()
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher(1 to 4))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(5)
      subscriber1.expectNext(1)
      subscriber1.expectNext(2)
      subscriber1.expectNext(3)
      subscriber1.expectNext(4)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), completedPublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(5)
      subscriber2.expectNext(1)
      subscriber2.expectNext(2)
      subscriber2.expectNext(3)
      subscriber2.expectNext(4)
      subscriber2.expectComplete()
    }

    "work with one delayed completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher(1 to 4))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(5)
      subscriber1.expectNext(1)
      subscriber1.expectNext(2)
      subscriber1.expectNext(3)
      subscriber1.expectNext(4)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToCompletePublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(5)
      subscriber2.expectNext(1)
      subscriber2.expectNext(2)
      subscriber2.expectNext(3)
      subscriber2.expectNext(4)
      subscriber2.expectComplete()
    }

    "work with one immediately failed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(failedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), failedPublisher)
      subscriber2.expectSubscriptionAndError(TestException)
    }

    "work with one nonempty and one delayed failed publisher" in assertAllStagesStopped {
      // This test and the next one are materialization order dependent and rely on the fact
      // that there are only 3 submodules in the graph that gets created and that an immutable
      // set (what they are stored in internally) of size 4 or less is an optimized version that
      // traverses in insertion order
      val subscriber = setup(nonemptyPublisher(1 to 4), soonToFailPublisher)
      subscriber.expectSubscription().request(5)

      var errorSignalled = false
      if (!errorSignalled) errorSignalled ||= subscriber.expectNextOrError(1, TestException).isLeft
      if (!errorSignalled) errorSignalled ||= subscriber.expectNextOrError(2, TestException).isLeft
      if (!errorSignalled) errorSignalled ||= subscriber.expectNextOrError(3, TestException).isLeft
      if (!errorSignalled) errorSignalled ||= subscriber.expectNextOrError(4, TestException).isLeft
      if (!errorSignalled) subscriber.expectSubscriptionAndError(TestException)
    }

    "work with one delayed failed and one nonempty publisher" in assertAllStagesStopped {
      // This test and the previous one are materialization order dependent and rely on the fact
      // that there are only 3 submodules in the graph that gets created and that an immutable
      // set (what they are stored in internally) of size 4 or less is an optimized version that
      // traverses in insertion order
      val subscriber = setup(soonToFailPublisher, nonemptyPublisher(1 to 4))
      subscriber.expectSubscription().request(5)

      var errorSignalled = false
      if (!errorSignalled) errorSignalled ||= subscriber.expectNextOrError(1, TestException).isLeft
      if (!errorSignalled) errorSignalled ||= subscriber.expectNextOrError(2, TestException).isLeft
      if (!errorSignalled) errorSignalled ||= subscriber.expectNextOrError(3, TestException).isLeft
      if (!errorSignalled) errorSignalled ||= subscriber.expectNextOrError(4, TestException).isLeft
      if (!errorSignalled) subscriber.expectSubscriptionAndError(TestException)
    }

    "correctly handle async errors in secondary upstream" in assertAllStagesStopped {
      val promise = Promise[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      FlowGraph.closed() { implicit b ⇒
        val concat = b add Concat[Int]()
        Source(List(1, 2, 3)) ~> concat.in(0)
        Source(promise.future) ~> concat.in(1)
        concat.out ~> Sink(subscriber)
      }.run()

      val subscription = subscriber.expectSubscription()
      subscription.request(4)
      subscriber.expectNext(1)
      subscriber.expectNext(2)
      subscriber.expectNext(3)
      promise.failure(TestException)
      subscriber.expectError(TestException)
    }

    "work with Source DSL" in {
      val testSource = Source(1 to 5).concat(Source(6 to 10)).grouped(1000)
      Await.result(testSource.runWith(Sink.head), 3.seconds) should ===(1 to 10)

      val runnable = testSource.toMat(Sink.ignore)(Keep.left)
      val (m1, m2) = runnable.run()
      m1.isInstanceOf[Unit] should be(true)
      m2.isInstanceOf[Unit] should be(true)

      runnable.mapMaterializedValue((_) ⇒ "boo").run() should be("boo")

    }

    "work with Flow DSL" in {
      val testFlow = Flow[Int].concat(Source(6 to 10)).grouped(1000)
      Await.result(Source(1 to 5).viaMat(testFlow)(Keep.both).runWith(Sink.head), 3.seconds) should ===(1 to 10)

      val runnable = Source(1 to 5).viaMat(testFlow)(Keep.both).to(Sink.ignore)
      val (m1, (m2, m3)) = runnable.run()
      m1.isInstanceOf[Unit] should be(true)
      m2.isInstanceOf[Unit] should be(true)
      m3.isInstanceOf[Unit] should be(true)

      runnable.mapMaterializedValue((_) ⇒ "boo").run() should be("boo")

    }

    "work with Flow DSL2" in {
      val testFlow = Flow[Int].concat(Source(6 to 10)).grouped(1000)
      Await.result(Source(1 to 5).viaMat(testFlow)(Keep.both).runWith(Sink.head), 3.seconds) should ===(1 to 10)

      val sink = testFlow.concat(Source(1 to 5)).toMat(Sink.ignore)(Keep.left).mapMaterializedValue[String] {
        case ((m1, m2), m3) ⇒
          m1.isInstanceOf[Unit] should be(true)
          m2.isInstanceOf[Unit] should be(true)
          m3.isInstanceOf[Unit] should be(true)
          "boo"
      }

      Source(10 to 15).runWith(sink) should be("boo")

    }
  }
}
