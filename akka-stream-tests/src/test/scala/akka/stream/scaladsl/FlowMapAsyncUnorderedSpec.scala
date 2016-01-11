/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.testkit.TestLatch
import akka.testkit.TestProbe
import akka.stream.scaladsl.OperationAttributes.supervisionStrategy
import akka.stream.Supervision.resumingDecider
import akka.stream.testkit.StreamTestKit.OnNext
import akka.stream.testkit.StreamTestKit.OnComplete
import akka.stream.impl.ReactiveStreamsCompliance

class FlowMapAsyncUnorderedSpec extends AkkaSpec {

  implicit val materializer = ActorFlowMaterializer()

  "A Flow with mapAsyncUnordered" must {

    "produce future elements in the order they are ready" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val latch = (1 to 4).map(_ -> TestLatch(1)).toMap
      val p = Source(1 to 4).mapAsyncUnordered(4, n ⇒ Future {
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
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 20).mapAsyncUnordered(4, n ⇒ Future {
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
      c.probe.within(3.seconds) {
        for (_ ← 2 to 20) got += c.expectNext()
      }

      got should be((1 to 20).toSet)
      c.expectComplete()
    }

    "signal future failure" in {
      val latch = TestLatch(1)
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsyncUnordered(4, n ⇒ Future {
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

    "signal error from mapAsyncUnordered" in {
      val latch = TestLatch(1)
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsyncUnordered(4, n ⇒
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
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5)
        .mapAsyncUnordered(4, n ⇒ Future {
          if (n == 3) throw new RuntimeException("err3") with NoStackTrace
          else n
        })
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      val expected = (OnComplete :: List(1, 2, 4, 5).map(OnNext.apply)).toSet
      c.probe.receiveN(5).toSet should be(expected)
    }

    "resume when mapAsyncUnordered throws" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5)
        .mapAsyncUnordered(4, n ⇒
          if (n == 3) throw new RuntimeException("err4") with NoStackTrace
          else Future(n))
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      val expected = (OnComplete :: List(1, 2, 4, 5).map(OnNext.apply)).toSet
      c.probe.receiveWhile(3.seconds, messages = 5) { case x ⇒ x }.toSet should be(expected)
    }

    "signal NPE when future is completed with null" in {
      val c = StreamTestKit.SubscriberProbe[String]()
      val p = Source(List("a", "b")).mapAsyncUnordered(4, elem ⇒ Future.successful(null)).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be(ReactiveStreamsCompliance.ElementMustNotBeNullMsg)
    }

    "resume when future is completed with null" in {
      val c = StreamTestKit.SubscriberProbe[String]()
      val p = Source(List("a", "b", "c"))
        .mapAsyncUnordered(4, elem ⇒ if (elem == "b") Future.successful(null) else Future.successful(elem))
        .withAttributes(supervisionStrategy(resumingDecider))
        .to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      for (elem ← List("a", "c")) c.expectNext(elem)
      c.expectComplete()
    }

    "should handle cancel properly" in {
      val pub = StreamTestKit.PublisherProbe[Int]()
      val sub = StreamTestKit.SubscriberProbe[Int]()

      Source(pub).mapAsyncUnordered(4, Future.successful).runWith(Sink(sub))

      val upstream = pub.expectSubscription()
      upstream.expectRequest()

      sub.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }
}
