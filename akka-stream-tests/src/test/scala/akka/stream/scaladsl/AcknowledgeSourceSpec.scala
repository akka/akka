/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.Utils._
import akka.stream.testkit.{ AkkaSpec, TestSubscriber }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent._
import akka.pattern.pipe

class AcknowledgeSourceSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  def assertSuccess(b: Boolean, fb: Future[Boolean]): Unit =
    Await.result(fb, 1.second) should be(b)

  "A AcknowledgeSource" must {

    "emit received messages to the stream" in {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(10, OverflowStrategy.fail).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      sub.request(2)
      assertSuccess(true, queue.offer(1))
      s.expectNext(1)
      assertSuccess(true, queue.offer(2))
      s.expectNext(2)
      assertSuccess(true, queue.offer(3))
      sub.cancel()
    }

    "buffer when needed" in {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(100, OverflowStrategy.dropHead).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      for (n ← 1 to 20) assertSuccess(true, queue.offer(n))
      sub.request(10)
      for (n ← 1 to 10) assertSuccess(true, queue.offer(n))
      sub.request(10)
      for (n ← 11 to 20) assertSuccess(true, queue.offer(n))

      for (n ← 200 to 399) assertSuccess(true, queue.offer(n))
      sub.request(100)
      for (n ← 300 to 399) assertSuccess(true, queue.offer(n))
      sub.cancel()
    }

    "not fail when 0 buffer space and demand is signalled" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(0, OverflowStrategy.dropHead).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      sub.request(1)
      assertSuccess(true, queue.offer(1))
      s.expectNext(1)
      sub.cancel()
    }

    "return false when can reject element to buffer" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(1, OverflowStrategy.dropNew).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      assertSuccess(true, queue.offer(1))
      assertSuccess(false, queue.offer(2))
      sub.request(1)
      s.expectNext(1)
      sub.cancel()
    }

    "wait when buffer is full and backpressure is on" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(2, OverflowStrategy.backpressure).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      assertSuccess(true, queue.offer(1))

      val addedSecond = queue.offer(2)

      addedSecond.pipeTo(testActor)
      expectNoMsg(300.millis)

      sub.request(1)
      s.expectNext(1)
      assertSuccess(true, addedSecond)

      sub.request(1)
      s.expectNext(2)

      sub.cancel()
    }

  }

}
