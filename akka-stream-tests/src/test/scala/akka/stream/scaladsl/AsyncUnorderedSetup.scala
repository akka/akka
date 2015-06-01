/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorFlowMaterializer
import akka.stream.ActorOperationAttributes._
import akka.stream.Supervision._
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.{ AkkaSpec, TestPublisher, TestSubscriber }
import akka.testkit.{ TestLatch, TestProbe }

import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, _ }
import scala.util.control.NoStackTrace

abstract class AsyncUnorderedSetup extends AkkaSpec {
  implicit val materializer = ActorFlowMaterializer()

  def functionToTest[T, Out, Mat](flow: Source[Out, Mat], parallelism: Int, f: Out ⇒ T): Source[T, Mat]

  def commonTests() = {

    "produce elements in the order they are ready" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val latch = (1 to 4).map(_ -> TestLatch(1)).toMap
      val p = functionToTest(Source(1 to 4), 4, (n: Int) ⇒ {
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

    "not run more functions than requested elements" in {
      val probe = TestProbe()
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = functionToTest(Source(1 to 20), 4, (n: Int) ⇒ {
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

    "signal function failure" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = functionToTest(Source(1 to 5), 4, (n: Int) ⇒ {
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

    "resume after function failure" in {
      implicit val ec = system.dispatcher
      functionToTest(Source(1 to 5), 4, (n: Int) ⇒ {
        if (n == 3) throw new RuntimeException("err3") with NoStackTrace
        else n
      })
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(10)
        .expectNextUnordered(1, 2, 4, 5)
        .expectComplete()
    }

    "finish after function thrown exception" in assertAllStagesStopped {
      Await.result(functionToTest(Source(1 to 3), 1, (n: Int) ⇒ {
        if (n == 3) throw new RuntimeException("err3b") with NoStackTrace
        else n
      }).withAttributes(supervisionStrategy(resumingDecider))
        .grouped(10)
        .runWith(Sink.head), 1.second) should be(Seq(1, 2))
    }

    "signal NPE when function is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = functionToTest(Source(List("a", "b")), 4, (n: String) ⇒ null).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be(ReactiveStreamsCompliance.ElementMustNotBeNullMsg)
    }

    "resume when function is completed with null" in {
      val c = TestSubscriber.manualProbe[String]()
      val p = functionToTest(Source(List("a", "b", "c")),
        4,
        (elem: String) ⇒ if (elem == "b") null else elem)
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (elem ← List("a", "c")) c.expectNext(elem)
      c.expectComplete()
    }

    "handle cancel properly" in assertAllStagesStopped {
      val pub = TestPublisher.manualProbe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()

      functionToTest(Source(pub), 4, identity[Int]).runWith(Sink(sub))

      val upstream = pub.expectSubscription()
      upstream.expectRequest()

      sub.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }
}
