/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.actor.{ Status }
import akka.pattern.pipe
import akka.stream._
import akka.stream.impl.QueueSource
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.testkit.{ AkkaSpec, TestProbe }
import scala.concurrent.duration._
import scala.concurrent._
import akka.Done

class QueueSourceSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  val pause = 300.millis

  def assertSuccess(f: Future[QueueOfferResult]): Unit = {
    f pipeTo testActor
    expectMsg(QueueOfferResult.Enqueued)
  }

  "A QueueSource" must {

    "emit received messages to the stream" in {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(10, OverflowStrategy.fail).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      for (i ← 1 to 3) {
        sub.request(1)
        assertSuccess(queue.offer(i))
        s.expectNext(i)
      }

      queue.watchCompletion().pipeTo(testActor)
      expectNoMsg(pause)

      sub.cancel()
      expectMsg(Done)
    }

    "buffer when needed" in {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(100, OverflowStrategy.dropHead).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      for (n ← 1 to 20) assertSuccess(queue.offer(n))
      sub.request(10)
      for (n ← 1 to 10) assertSuccess(queue.offer(n))
      sub.request(10)
      for (n ← 11 to 20) assertSuccess(queue.offer(n))

      for (n ← 200 to 399) assertSuccess(queue.offer(n))
      sub.request(100)
      for (n ← 300 to 399) assertSuccess(queue.offer(n))
      sub.cancel()
    }

    "not fail when 0 buffer space and demand is signalled" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(0, OverflowStrategy.dropHead).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      sub.request(1)

      assertSuccess(queue.offer(1))

      sub.cancel()
    }

    "wait for demand when buffer is 0" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(0, OverflowStrategy.dropHead).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      queue.offer(1).pipeTo(testActor)
      expectNoMsg(pause)
      sub.request(1)
      expectMsg(QueueOfferResult.Enqueued)
      s.expectNext(1)
      sub.cancel()
    }

    "finish offer and complete futures when stream completed" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(0, OverflowStrategy.dropHead).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      queue.watchCompletion.pipeTo(testActor)
      queue.offer(1) pipeTo testActor
      expectNoMsg(pause)

      sub.cancel()

      expectMsgAllOf(QueueOfferResult.QueueClosed, Done)
    }

    "fail stream on buffer overflow in fail mode" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(1, OverflowStrategy.fail).to(Sink.fromSubscriber(s)).run()
      s.expectSubscription

      queue.offer(1)
      queue.offer(2)
      s.expectError()
    }

    "remember pull from downstream to send offered element immediately" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val probe = TestProbe()
      val queue = TestSourceStage(new QueueSource[Int](1, OverflowStrategy.dropHead), probe)
        .to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      sub.request(1)
      probe.expectMsg(GraphStageMessages.Pull)
      assertSuccess(queue.offer(1))
      s.expectNext(1)
      sub.cancel()
    }

    "fail offer future if user does not wait in backpressure mode" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(5, OverflowStrategy.backpressure).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      for (i ← 1 to 5) assertSuccess(queue.offer(i))

      queue.offer(6).pipeTo(testActor)
      expectNoMsg(pause)

      val future = queue.offer(7)
      future.onFailure { case e ⇒ e.isInstanceOf[IllegalStateException] should ===(true) }
      future.onSuccess { case _ ⇒ fail() }
      Await.ready(future, pause)

      sub.request(1)
      s.expectNext(1)
      expectMsg(QueueOfferResult.Enqueued)
      sub.cancel()
    }

    "complete watching future with failure if stream failed" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(1, OverflowStrategy.fail).to(Sink.fromSubscriber(s)).run()
      queue.watchCompletion().pipeTo(testActor)
      queue.offer(1) //need to wait when first offer is done as initialization can be done in this moment
      queue.offer(2)
      expectMsgClass(classOf[Status.Failure])
    }

    "return false when elemen was not added to buffer" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(1, OverflowStrategy.dropNew).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      queue.offer(1)
      queue.offer(2) pipeTo testActor
      expectMsg(QueueOfferResult.Dropped)

      sub.request(1)
      s.expectNext(1)
      sub.cancel()
    }

    "wait when buffer is full and backpressure is on" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(1, OverflowStrategy.backpressure).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      assertSuccess(queue.offer(1))

      queue.offer(2) pipeTo testActor
      expectNoMsg(pause)

      sub.request(1)
      s.expectNext(1)

      sub.request(1)
      s.expectNext(2)
      expectMsg(QueueOfferResult.Enqueued)

      sub.cancel()
    }

    "fail offer future when stream is completed" in assertAllStagesStopped {
      val s = TestSubscriber.manualProbe[Int]()
      val queue = Source.queue(1, OverflowStrategy.dropNew).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription
      queue.watchCompletion().pipeTo(testActor)
      sub.cancel()
      expectMsg(Done)

      queue.offer(1).onFailure { case e ⇒ e.isInstanceOf[IllegalStateException] should ===(true) }
    }

  }

}
