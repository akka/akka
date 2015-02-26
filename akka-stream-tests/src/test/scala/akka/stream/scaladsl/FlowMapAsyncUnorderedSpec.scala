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

class FlowMapAsyncUnorderedSpec extends AkkaSpec {

  implicit val materializer = ActorFlowMaterializer()

  "A Flow with mapAsyncUnordered" must {

    "produce future elements in the order they are ready" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val latch = (1 to 4).map(_ -> TestLatch(1)).toMap
      val p = Source(1 to 4).mapAsyncUnordered(n ⇒ Future {
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
      val p = Source(1 to 20).mapAsyncUnordered(n ⇒ Future {
        probe.ref ! n
        n
      }).to(Sink(c)).run()
      val sub = c.expectSubscription()
      // nothing before requested
      probe.expectNoMsg(500.millis)
      sub.request(1)
      val elem1 = probe.expectMsgType[Int]
      probe.expectNoMsg(500.millis)
      sub.request(2)
      val elem2 = probe.expectMsgType[Int]
      val elem3 = probe.expectMsgType[Int]
      probe.expectNoMsg(500.millis)
      sub.request(100)
      (probe.receiveN(17).toSet + elem1 + elem2 + elem3) should be((1 to 20).toSet)
      probe.expectNoMsg(200.millis)

      c.probe.receiveN(20).toSet should be((1 to 20).map(StreamTestKit.OnNext.apply).toSet)
      c.expectComplete()
    }

    "signal future failure" in {
      val latch = TestLatch(1)
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsyncUnordered(n ⇒ Future {
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
      val p = Source(1 to 5).mapAsyncUnordered(n ⇒
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
      val p = Source(1 to 5).section(supervisionStrategy(resumingDecider))(_.mapAsyncUnordered(n ⇒ Future {
        if (n == 3) throw new RuntimeException("err3") with NoStackTrace
        else n
      })).to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      val expected = (OnComplete :: List(1, 2, 4, 5).map(OnNext.apply)).toSet
      c.probe.receiveN(5).toSet should be(expected)
    }

    "resume when mapAsyncUnordered throws" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).section(supervisionStrategy(resumingDecider))(_.mapAsyncUnordered(n ⇒
        if (n == 3) throw new RuntimeException("err4") with NoStackTrace
        else Future(n))).
        to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      val expected = (OnComplete :: List(1, 2, 4, 5).map(OnNext.apply)).toSet
      c.probe.receiveN(5).toSet should be(expected)
    }

  }
}
