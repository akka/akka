/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.stream.ActorMaterializer
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._
import akka.stream.testkit.Utils._
import akka.testkit.TestLatch
import akka.testkit.TestProbe
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.Supervision.resumingDecider
import akka.stream.impl.ReactiveStreamsCompliance

class FlowMapAsyncUnorderedSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A Flow with mapAsyncUnordered" must {

    "produce future elements in the order they are ready" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val latch = (1 to 4).map(_ -> TestLatch(1)).toMap
      val p = Source(1 to 4).mapAsyncUnordered(4)(n ⇒ Future {
        Await.ready(latch(n), 5.seconds)
        n
      }).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(5)
      latch(2).countDown()
      c.expectNext(2)
      latch(4).countDown()
      c.expectNext(4)
      latch(3).countDown()
      c.expectNext(3)
      latch(1).countDown()
      c.expectNext(1)
      c.expectComplete()
    }

    "not run more futures than requested elements" in {
      val probe = TestProbe()
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 20).mapAsyncUnordered(4)(n ⇒ Future {
        probe.ref ! n
        n
      }).to(Sink(c)).run()
      val sub = c.expectSubscription()
      // first four run immediately
      probe.expectMsgAllOf(1, 2, 3, 4)
      c.expectNoMsg(200.millis)
      probe.expectNoMsg(Duration.Zero)
      sub.request(1)
      var got = Set(c.expectNext())
      probe.expectMsg(5)
      probe.expectNoMsg(500.millis)
      sub.request(25)
      probe.expectMsgAllOf(6 to 20: _*)
      c.within(3.seconds) {
        for (_ ← 2 to 20) got += c.expectNext()
      }

      got should be((1 to 20).toSet)
      c.expectComplete()
    }

    "signal future failure" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsyncUnordered(4)(n ⇒ Future {
        if (n == 3) throw new RuntimeException("err1") with NoStackTrace
        else {
          Await.ready(latch, 10.seconds)
          n
        }
      }).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be("err1")
      latch.countDown()
    }

    "signal error from mapAsyncUnordered" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsyncUnordered(4)(n ⇒
        if (n == 3) throw new RuntimeException("err2") with NoStackTrace
        else {
          Future {
            Await.ready(latch, 10.seconds)
            n
          }
        }).
        to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be("err2")
      latch.countDown()
    }

    "resume after future failure" in {
      implicit val ec = system.dispatcher
      Source(1 to 5)
        .mapAsyncUnordered(4)(n ⇒ Future {
          if (n == 3) throw new RuntimeException("err3") with NoStackTrace
          else n
        })
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(10)
        .expectNextUnordered(1, 2, 4, 5)
        .expectComplete()
    }

    "resume after multiple failures" in assertAllStagesStopped {
      val futures: List[Future[String]] = List(
        Future.failed(Utils.TE("failure1")),
        Future.failed(Utils.TE("failure2")),
        Future.failed(Utils.TE("failure3")),
        Future.failed(Utils.TE("failure4")),
        Future.failed(Utils.TE("failure5")),
        Future.successful("happy!"))

      Await.result(
        Source(futures)
          .mapAsyncUnordered(2)(identity).withAttributes(supervisionStrategy(resumingDecider))
          .runWith(Sink.head), 3.seconds) should ===("happy!")
    }

    "finish after future failure" in assertAllStagesStopped {
      import system.dispatcher
      Await.result(Source(1 to 3).mapAsyncUnordered(1)(n ⇒ Future {
        if (n == 3) throw new RuntimeException("err3b") with NoStackTrace
        else n
      }).withAttributes(supervisionStrategy(resumingDecider))
        .grouped(10)
        .runWith(Sink.head), 1.second) should be(Seq(1, 2))
    }

    "resume when mapAsyncUnordered throws" in {
      implicit val ec = system.dispatcher
      Source(1 to 5)
        .mapAsyncUnordered(4)(n ⇒
          if (n == 3) throw new RuntimeException("err4") with NoStackTrace
          else Future(n))
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(10)
        .expectNextUnordered(1, 2, 4, 5)
        .expectComplete()
    }

    "signal NPE when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b")).mapAsyncUnordered(4)(elem ⇒ Future.successful(null)).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be(ReactiveStreamsCompliance.ElementMustNotBeNullMsg)
    }

    "resume when future is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = Source(List("a", "b", "c"))
        .mapAsyncUnordered(4)(elem ⇒ if (elem == "b") Future.successful(null) else Future.successful(elem))
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (elem ← List("a", "c")) c.expectNext(elem)
      c.expectComplete()
    }

    "should handle cancel properly" in assertAllStagesStopped {
      val pub = TestPublisher.manualProbe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()

      Source(pub).mapAsyncUnordered(4)(Future.successful).runWith(Sink(sub))

      val upstream = pub.expectSubscription()
      upstream.expectRequest()

      sub.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }
}
