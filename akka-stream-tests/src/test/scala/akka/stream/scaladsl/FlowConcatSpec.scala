/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.{ BaseTwoStreamsSetup, TestPublisher, TestSubscriber }
import org.reactivestreams.Publisher

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class FlowConcatSpec extends BaseTwoStreamsSetup {

  override type Outputs = Int

  override def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = TestSubscriber.probe[Outputs]()
    Source(p1).concat(Source(p2)).runWith(Sink(subscriber))
    subscriber
  }

  "A Concat for Flow " must {

    "be able to concat Flow with a Source" in {
      val f1: Flow[Int, String, _] = Flow[Int].map(_.toString + "-s")
      val s1: Source[Int, _] = Source(List(1, 2, 3))
      val s2: Source[String, _] = Source(List(4, 5, 6)).map(_.toString + "-s")

      val subs = TestSubscriber.manualProbe[Any]()
      val subSink = Sink.publisher[Any](false)

      val (_, res) = f1.concat(s2).runWith(s1, subSink)

      res.subscribe(subs)
      val sub = subs.expectSubscription()
      sub.request(9)
      (1 to 6).foreach(e ⇒ subs.expectNext(e.toString + "-s"))
      subs.expectComplete()
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher(1 to 4))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(5)
      (1 to 4).foreach(subscriber1.expectNext)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), completedPublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(5)
      (1 to 4).foreach(subscriber2.expectNext)
      subscriber2.expectComplete()
    }

    "work with one delayed completed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher(1 to 4))
      val subscription1 = subscriber1.expectSubscription()
      subscription1.request(5)
      (1 to 4).foreach(subscriber1.expectNext)
      subscriber1.expectComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToCompletePublisher)
      val subscription2 = subscriber2.expectSubscription()
      subscription2.request(5)
      (1 to 4).foreach(subscriber2.expectNext)
      subscriber2.expectComplete()
    }

    "work with one immediately failed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber1 = setup(failedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectSubscriptionAndError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), failedPublisher)
      subscriber2.expectSubscriptionAndError(TestException)
    }

    "work with one nonempty and one delayed failed publisher" in assertAllStagesStopped {
      val subscriber = setup(nonemptyPublisher(1 to 4), soonToFailPublisher)
      subscriber.expectSubscription().request(5)

      val errorSignalled = (1 to 4).foldLeft(false)((errorSignalled, e) ⇒
        if (!errorSignalled) subscriber.expectNextOrError(1, TestException).isLeft else true)
      if (!errorSignalled) subscriber.expectSubscriptionAndError(TestException)
    }

    "work with one delayed failed and one nonempty publisher" in assertAllStagesStopped {
      val subscriber = setup(soonToFailPublisher, nonemptyPublisher(1 to 4))
      subscriber.expectSubscription().request(5)

      val errorSignalled = (1 to 4).foldLeft(false)((errorSignalled, e) ⇒
        if (!errorSignalled) subscriber.expectNextOrError(1, TestException).isLeft else true)
      if (!errorSignalled) subscriber.expectSubscriptionAndError(TestException)
    }

    "correctly handle async errors in secondary upstream" in assertAllStagesStopped {
      val promise = Promise[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()
      Source(List(1, 2, 3)).concat(Source(promise.future)).runWith(Sink(subscriber))

      val subscription = subscriber.expectSubscription()
      subscription.request(4)
      (1 to 3).foreach(subscriber.expectNext)
      promise.failure(TestException)
      subscriber.expectError(TestException)
    }

    "work with Source DSL" in {
      val testSource = Source(1 to 5).concatMat(Source(6 to 10))(Keep.both).grouped(1000)
      Await.result(testSource.runWith(Sink.head), 3.seconds) should ===(1 to 10)

      val runnable = testSource.toMat(Sink.ignore)(Keep.left)
      val (m1, m2) = runnable.run()
      m1.isInstanceOf[Unit] should be(true)
      m2.isInstanceOf[Unit] should be(true)

      runnable.mapMaterializedValue((_) ⇒ "boo").run() should be("boo")
    }

    "work with Flow DSL" in {
      val testFlow = Flow[Int].concatMat(Source(6 to 10))(Keep.both).grouped(1000)
      Await.result(Source(1 to 5).viaMat(testFlow)(Keep.both).runWith(Sink.head), 3.seconds) should ===(1 to 10)

      val runnable = Source(1 to 5).viaMat(testFlow)(Keep.both).to(Sink.ignore)
      val (m1, (m2, m3)) = runnable.run()
      m1.isInstanceOf[Unit] should be(true)
      m2.isInstanceOf[Unit] should be(true)
      m3.isInstanceOf[Unit] should be(true)

      runnable.mapMaterializedValue((_) ⇒ "boo").run() should be("boo")
    }

    "work with Flow DSL2" in {
      val testFlow = Flow[Int].concatMat(Source(6 to 10))(Keep.both).grouped(1000)
      Await.result(Source(1 to 5).viaMat(testFlow)(Keep.both).runWith(Sink.head), 3.seconds) should ===(1 to 10)

      val sink = testFlow.concatMat(Source(1 to 5))(Keep.both).to(Sink.ignore).mapMaterializedValue[String] {
        case ((m1, m2), m3) ⇒
          m1.isInstanceOf[Unit] should be(true)
          m2.isInstanceOf[Unit] should be(true)
          m3.isInstanceOf[Unit] should be(true)
          "boo"
      }
      Source(10 to 15).runWith(sink) should be("boo")
    }

    "subscribe at once to initial source and to one that it's concat to" in {
      val publisher1 = TestPublisher.probe[Int]()
      val publisher2 = TestPublisher.probe[Int]()
      val probeSink = Source(publisher1).concat(Source(publisher2))
        .runWith(TestSink.probe[Int])

      val sub1 = publisher1.expectSubscription()
      val sub2 = publisher2.expectSubscription()
      val subSink = probeSink.expectSubscription()

      sub1.sendNext(1)
      subSink.request(1)
      probeSink.expectNext(1)
      sub1.sendComplete()

      sub2.sendNext(2)
      subSink.request(1)
      probeSink.expectNext(2)
      sub2.sendComplete()

      probeSink.expectComplete()
    }
  }
}
