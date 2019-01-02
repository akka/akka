/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.Done
import akka.stream.Attributes._
import akka.stream.OverflowStrategies.EmitEarly
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber }
import akka.stream._
import akka.testkit.TimingTest

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class FlowDelaySpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()

  "A Delay" must {

    "deliver elements with some time shift" taggedAs TimingTest in {
      Await.result(
        Source(1 to 10).delay(1.seconds).grouped(100).runWith(Sink.head),
        1200.millis) should ===(1 to 10)
    }

    "add delay to initialDelay if exists upstream" taggedAs TimingTest in {
      Source(1 to 10).initialDelay(1.second).delay(1.second).runWith(TestSink.probe[Int])
        .request(10)
        .expectNoMsg(1800.millis)
        .expectNext(300.millis, 1)
        .expectNextN(2 to 10)
        .expectComplete()
    }

    "deliver element after time passed from actual receiving element" in {
      Source(1 to 3).delay(300.millis).runWith(TestSink.probe[Int])
        .request(2)
        .expectNoMsg(200.millis) //delay
        .expectNext(200.millis, 1) //delayed element
        .expectNext(100.millis, 2) //buffered element
        .expectNoMsg(200.millis)
        .request(1)
        .expectNext(3) //buffered element
        .expectComplete()
    }

    "deliver elements with delay for slow stream" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      val p = TestPublisher.manualProbe[Int]()

      Source.fromPublisher(p).delay(300.millis).to(Sink.fromSubscriber(c)).run()
      val cSub = c.expectSubscription()
      val pSub = p.expectSubscription()
      cSub.request(100)
      pSub.sendNext(1)
      c.expectNoMsg(200.millis)
      c.expectNext(1)
      pSub.sendNext(2)
      c.expectNoMsg(200.millis)
      c.expectNext(2)
      pSub.sendComplete()
      c.expectComplete()
    }

    "deliver delayed elements that arrive within the same timeout as preceding group of elements" taggedAs TimingTest in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      val p = TestPublisher.manualProbe[Int]()

      Source.fromPublisher(p).delay(300.millis).to(Sink.fromSubscriber(c)).run()
      val cSub = c.expectSubscription()
      val pSub = p.expectSubscription()
      cSub.request(100)
      pSub.sendNext(1)
      pSub.sendNext(2)
      c.expectNoMsg(200.millis)
      pSub.sendNext(3)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNoMsg(150.millis)
      c.expectNext(3)
      pSub.sendComplete()
      c.expectComplete()
    }

    "drop tail for internal buffer if it's full in DropTail mode" in assertAllStagesStopped {
      Await.result(
        Source(1 to 20).delay(1.seconds, DelayOverflowStrategy.dropTail).withAttributes(inputBuffer(16, 16))
          .grouped(100)
          .runWith(Sink.head),
        1200.millis) should ===((1 to 15).toList :+ 20)
    }

    "drop head for internal buffer if it's full in DropHead mode" in assertAllStagesStopped {
      Await.result(
        Source(1 to 20).delay(1.seconds, DelayOverflowStrategy.dropHead).withAttributes(inputBuffer(16, 16))
          .grouped(100)
          .runWith(Sink.head),
        1200.millis) should ===(5 to 20)
    }

    "clear all for internal buffer if it's full in DropBuffer mode" in assertAllStagesStopped {
      Await.result(
        Source(1 to 20).delay(1.seconds, DelayOverflowStrategy.dropBuffer).withAttributes(inputBuffer(16, 16))
          .grouped(100)
          .runWith(Sink.head),
        1200.millis) should ===(17 to 20)
    }

    "pass elements with delay through normally in backpressured mode" in assertAllStagesStopped {
      Source(1 to 3).delay(300.millis, DelayOverflowStrategy.backpressure).withAttributes(inputBuffer(1, 1)).runWith(TestSink.probe[Int])
        .request(5)
        .expectNoMsg(200.millis)
        .expectNext(200.millis, 1)
        .expectNoMsg(200.millis)
        .expectNext(200.millis, 2)
        .expectNoMsg(200.millis)
        .expectNext(200.millis, 3)
    }

    "fail on overflow in Fail mode" in assertAllStagesStopped {
      Source(1 to 20).delay(300.millis, DelayOverflowStrategy.fail)
        .withAttributes(inputBuffer(16, 16))
        .runWith(TestSink.probe[Int])
        .request(100)
        .expectError(new BufferOverflowException("Buffer overflow for delay operator (max capacity was: 16)!"))

    }

    "emit early when buffer is full and in EmitEarly mode" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      val p = TestPublisher.manualProbe[Int]()

      Source.fromPublisher(p).delay(10.seconds, DelayOverflowStrategy.emitEarly).withAttributes(inputBuffer(16, 16)).to(Sink.fromSubscriber(c)).run()
      val cSub = c.expectSubscription()
      val pSub = p.expectSubscription()
      cSub.request(20)

      for (i ← 1 to 16) pSub.sendNext(i)
      c.expectNoMsg(300.millis)
      pSub.sendNext(17)
      c.expectNext(100.millis, 1)
      //fail will terminate despite of non empty internal buffer
      pSub.sendError(new RuntimeException() with NoStackTrace)
    }

    "properly delay according to buffer size" taggedAs TimingTest in {
      import akka.pattern.pipe
      import system.dispatcher

      // With a buffer size of 1, delays add up
      Source(1 to 5)
        .delay(500.millis, DelayOverflowStrategy.backpressure)
        .withAttributes(Attributes.inputBuffer(initial = 1, max = 1))
        .runWith(Sink.ignore).pipeTo(testActor)

      expectNoMsg(2.seconds)
      expectMsg(Done)

      // With a buffer large enough to hold all arriving elements, delays don't add up
      Source(1 to 100)
        .delay(1.second, DelayOverflowStrategy.backpressure)
        .withAttributes(Attributes.inputBuffer(initial = 100, max = 100))
        .runWith(Sink.ignore).pipeTo(testActor)

      expectMsg(Done)

      // Delays that are already present are preserved when buffer is large enough
      Source.tick(100.millis, 100.millis, ()).take(10)
        .delay(1.second, DelayOverflowStrategy.backpressure)
        .withAttributes(Attributes.inputBuffer(initial = 10, max = 10))
        .runWith(Sink.ignore).pipeTo(testActor)

      expectNoMsg(900.millis)
      expectMsg(Done)
    }

    "not overflow buffer when DelayOverflowStrategy.backpressure" in {
      val probe = Source(1 to 6).delay(100.millis, DelayOverflowStrategy.backpressure)
        .withAttributes(Attributes.inputBuffer(2, 2))
        .throttle(1, 200.millis, 1, ThrottleMode.Shaping)
        .runWith(TestSink.probe)

      probe.request(10)
        .expectNextN(1 to 6)
        .expectComplete()
    }

    "not drop messages on overflow when EmitEarly" in {
      val probe = Source(1 to 2)
        .delay(1.second, EmitEarly).withAttributes(Attributes.inputBuffer(1, 1))
        .runWith(TestSink.probe)

      probe.request(10)
        .expectNextN(1 to 2)
        .expectComplete()
    }
  }
}
