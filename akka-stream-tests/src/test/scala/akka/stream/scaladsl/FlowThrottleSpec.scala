/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import akka.stream.ThrottleMode.{ Enforcing, Shaping }
import akka.stream._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.util.ByteString
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NoStackTrace

import akka.Done
import akka.testkit.TimingTest

class FlowThrottleSpec extends StreamSpec {
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withInputBuffer(1, 1))

  def genByteString(length: Int) =
    ByteString(new Random().shuffle(0 to 255).take(length).map(_.toByte).toArray)

  "Throttle for single cost elements" must {
    "work for the happy case" in assertAllStagesStopped {
      //Source(1 to 5).throttle(1, 100.millis, 0, Shaping)
      Source(1 to 5).throttle(19, 1000.millis, -1, Shaping)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }

    "accept very high rates" in assertAllStagesStopped {
      Source(1 to 5).throttle(1, 1.nanos, 0, Shaping)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }

    "accept very low rates" in assertAllStagesStopped {
      Source(1 to 5).throttle(1, 100.days, 1, Shaping)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1)
        .expectNoMsg(100.millis)
        .cancel() // We won't wait 100 days, sorry
    }

    "work if there are two throttles in different streams" in assertAllStagesStopped {
      val sharedThrottle = Flow[Int].throttle(1, 1.day, 1, Enforcing)

      // If there is accidental shared state then we would not be able to pass through the single element
      Source.single(1)
        .via(sharedThrottle)
        .via(sharedThrottle)
        .runWith(Sink.seq).futureValue should ===(Seq(1))

      // It works with a new stream, too
      Source.single(2)
        .via(sharedThrottle)
        .via(sharedThrottle)
        .runWith(Sink.seq).futureValue should ===(Seq(2))

    }

    "emit single element per tick" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()

      Source.fromPublisher(upstream).throttle(1, 300.millis, 0, Shaping).runWith(Sink.fromSubscriber(downstream))

      downstream.request(20)
      upstream.sendNext(1)
      downstream.expectNoMsg(150.millis)
      downstream.expectNext(1)

      upstream.sendNext(2)
      downstream.expectNoMsg(150.millis)
      downstream.expectNext(2)

      upstream.sendComplete()
      downstream.expectComplete()
    }

    "not send downstream if upstream does not emit element" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(1, 300.millis, 0, Shaping).runWith(Sink.fromSubscriber(downstream))

      downstream.request(2)
      upstream.sendNext(1)
      downstream.expectNext(1)

      downstream.expectNoMsg(300.millis)
      upstream.sendNext(2)
      downstream.expectNext(2)

      upstream.sendComplete()
    }

    "cancel when downstream cancels" in assertAllStagesStopped {
      val downstream = TestSubscriber.probe[Int]()
      Source(1 to 10).throttle(1, 300.millis, 0, Shaping).runWith(Sink.fromSubscriber(downstream))
      downstream.cancel()
    }

    "send elements downstream as soon as time comes" in assertAllStagesStopped {
      val probe = Source(1 to 10).throttle(2, 750.millis, 0, Shaping).runWith(TestSink.probe[Int])
        .request(5)
      probe.receiveWithin(900.millis) should be(Seq(1, 2))
      probe.expectNoMsg(150.millis)
        .expectNext(3)
        .expectNoMsg(150.millis)
        .expectNext(4)
        .cancel()
    }

    "burst according to its maximum if enough time passed" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(1, 200.millis, 5, Shaping).runWith(Sink.fromSubscriber(downstream))

      // Exhaust bucket first
      downstream.request(5)
      (1 to 5) foreach upstream.sendNext
      downstream.receiveWithin(300.millis, 5) should be(1 to 5)

      downstream.request(5)
      downstream.expectNoMsg(1200.millis)
      for (i ← 7 to 11) upstream.sendNext(i)
      downstream.receiveWithin(300.millis, 5) should be(7 to 11)
      downstream.cancel()
    }

    "burst some elements if have enough time" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(1, 200.millis, 5, Shaping).runWith(Sink.fromSubscriber(downstream))

      // Exhaust bucket first
      downstream.request(5)
      (1 to 5) foreach upstream.sendNext
      downstream.receiveWithin(300.millis, 5) should be(1 to 5)

      downstream.request(1)
      upstream.sendNext(6)
      downstream.expectNoMsg(100.millis)
      downstream.expectNext(6)
      downstream.expectNoMsg(500.millis) //wait to receive 2 in burst afterwards
      downstream.request(5)
      for (i ← 7 to 10) upstream.sendNext(i)
      downstream.receiveWithin(100.millis, 2) should be(Seq(7, 8))
      downstream.cancel()
    }

    "throw exception when exceeding throughput in enforced mode" in assertAllStagesStopped {
      Await.result(
        Source(1 to 5).throttle(1, 200.millis, 5, Enforcing).runWith(Sink.seq),
        2.seconds) should ===(1 to 5) // Burst is 5 so this will not fail

      an[RateExceededException] shouldBe thrownBy {
        Await.result(
          Source(1 to 6).throttle(1, 200.millis, 5, Enforcing).runWith(Sink.ignore),
          2.seconds)
      }
    }

    "properly combine shape and throttle modes" in assertAllStagesStopped {
      Source(1 to 5).throttle(1, 100.millis, 5, Shaping)
        .throttle(1, 100.millis, 5, Enforcing)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }
  }

  "Throttle for various cost elements" must {
    "work for happy case" in assertAllStagesStopped {
      Source(1 to 5).throttle(1, 100.millis, 0, (_) ⇒ 1, Shaping)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }

    "emit elements according to cost" in assertAllStagesStopped {
      val list = (1 to 4).map(_ * 2).map(genByteString)
      Source(list).throttle(2, 200.millis, 0, _.length, Shaping)
        .runWith(TestSink.probe[ByteString])
        .request(4)
        .expectNext(list(0))
        .expectNoMsg(300.millis)
        .expectNext(list(1))
        .expectNoMsg(500.millis)
        .expectNext(list(2))
        .expectNoMsg(700.millis)
        .expectNext(list(3))
        .expectComplete()
    }

    "not send downstream if upstream does not emit element" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(2, 300.millis, 0, identity, Shaping).runWith(Sink.fromSubscriber(downstream))

      downstream.request(2)
      upstream.sendNext(1)
      downstream.expectNext(1)

      downstream.expectNoMsg(300.millis)
      upstream.sendNext(2)
      downstream.expectNext(2)

      upstream.sendComplete()
    }

    "cancel when downstream cancels" in assertAllStagesStopped {
      val downstream = TestSubscriber.probe[Int]()
      Source(1 to 10).throttle(2, 200.millis, 0, identity, Shaping).runWith(Sink.fromSubscriber(downstream))
      downstream.cancel()
    }

    "send elements downstream as soon as time comes" in assertAllStagesStopped {
      val probe = Source(1 to 10).throttle(4, 500.millis, 0, _ ⇒ 2, Shaping).runWith(TestSink.probe[Int])
        .request(5)
      probe.receiveWithin(600.millis) should be(Seq(1, 2))
      probe.expectNoMsg(100.millis)
        .expectNext(3)
        .expectNoMsg(100.millis)
        .expectNext(4)
        .cancel()
    }

    "burst according to its maximum if enough time passed" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(2, 400.millis, 5, (_) ⇒ 1, Shaping).runWith(Sink.fromSubscriber(downstream))

      // Exhaust bucket first
      downstream.request(5)
      (1 to 5) foreach upstream.sendNext
      downstream.receiveWithin(300.millis, 5) should be(1 to 5)

      downstream.request(1)
      upstream.sendNext(6)
      downstream.expectNoMsg(100.millis)
      downstream.expectNext(6)
      downstream.request(5)
      downstream.expectNoMsg(1200.millis)
      for (i ← 7 to 11) upstream.sendNext(i)
      downstream.receiveWithin(300.millis, 5) should be(7 to 11)
      downstream.cancel()
    }

    "burst some elements if have enough time" in assertAllStagesStopped {
      val upstream = TestPublisher.probe[Int]()
      val downstream = TestSubscriber.probe[Int]()
      Source.fromPublisher(upstream).throttle(2, 400.millis, 5, (e) ⇒ if (e < 9) 1 else 20, Shaping).runWith(Sink.fromSubscriber(downstream))

      // Exhaust bucket first
      downstream.request(5)
      (1 to 5) foreach upstream.sendNext
      downstream.receiveWithin(300.millis, 5) should be(1 to 5)

      downstream.request(1)
      upstream.sendNext(6)
      downstream.expectNoMsg(100.millis)
      downstream.expectNext(6)
      downstream.expectNoMsg(500.millis) //wait to receive 2 in burst afterwards
      downstream.request(5)
      for (i ← 7 to 9) upstream.sendNext(i)
      downstream.receiveWithin(200.millis, 2) should be(Seq(7, 8))
      downstream.cancel()
    }

    "throw exception when exceeding throughput in enforced mode" in assertAllStagesStopped {
      Await.result(
        Source(1 to 4).throttle(2, 200.millis, 10, identity, Enforcing).runWith(Sink.seq),
        2.seconds) should ===(1 to 4) // Burst is 10 so this will not fail

      an[RateExceededException] shouldBe thrownBy {
        Await.result(
          Source(1 to 6).throttle(2, 200.millis, 0, identity, Enforcing).runWith(Sink.ignore),
          2.seconds)
      }
    }

    "properly combine shape and enforce modes" in assertAllStagesStopped {
      Source(1 to 5).throttle(2, 200.millis, 0, identity, Shaping)
        .throttle(1, 100.millis, 5, Enforcing)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }

    "handle rate calculation function exception" in assertAllStagesStopped {
      val ex = new RuntimeException with NoStackTrace
      Source(1 to 5).throttle(2, 200.millis, 0, (_) ⇒ { throw ex }, Shaping)
        .throttle(1, 100.millis, 5, Enforcing)
        .runWith(TestSink.probe[Int])
        .request(5)
        .expectError(ex)
    }

    "work for real scenario with automatic burst size" taggedAs TimingTest in assertAllStagesStopped {
      val startTime = System.nanoTime()
      val counter1 = new AtomicInteger
      val timestamp1 = new AtomicLong(System.nanoTime())
      val expectedMinRate = new AtomicInteger
      val expectedMaxRate = new AtomicInteger
      val (ref, done) = Source.actorRef[Int](bufferSize = 100000, OverflowStrategy.fail)
        .throttle(300, 1000.millis)
        .toMat(Sink.foreach { elem ⇒
          val now = System.nanoTime()
          val n1 = counter1.incrementAndGet()
          val duration1Millis = (now - timestamp1.get) / 1000 / 1000
          if (duration1Millis >= 500) {
            val rate = n1 * 1000.0 / duration1Millis
            info(f"burst rate after ${(now - startTime).nanos.toMillis} ms at element $elem: $rate%2.2f elements/s ($n1)")
            timestamp1.set(now)
            counter1.set(0)
            if (rate < expectedMinRate.get)
              throw new RuntimeException(s"Too low rate, got $rate, expected min ${expectedMinRate.get}, " +
                s"after ${(now - startTime).nanos.toMillis} ms at element $elem")
            if (rate > expectedMaxRate.get)
              throw new RuntimeException(s"Too high rate, got $rate, expected max ${expectedMaxRate.get}, " +
                s"after ${(now - startTime).nanos.toMillis} ms at element $elem")
          }
        })(Keep.both)
        .run()

      expectedMaxRate.set(200) // sleep (at least) 5 ms between each element
      (1 to 2700).foreach { n ⇒
        if (!done.isCompleted) {
          ref ! n
          val now = System.nanoTime()
          val elapsed = (now - startTime).nanos
          val elapsedMs = elapsed.toMillis
          if (elapsedMs >= 500 && elapsedMs <= 3000) {
            expectedMinRate.set(100)
          } else if (elapsedMs >= 3000 && elapsedMs <= 5000) {
            expectedMaxRate.set(350) // could be up to 600 / s, but should be limited by the throttle
            if (elapsedMs > 4000) expectedMinRate.set(250)
          } else if (elapsedMs > 5000 && elapsedMs <= 8500) {
            expectedMinRate.set(100)
          } else if (elapsedMs > 10000) {
            expectedMaxRate.set(200)
          }

          // higher rate for a few seconds
          if (elapsedMs >= 3000 && elapsedMs <= 5000) {
            // could be up to 600 / s, but should be limited by the throttle
            if (n % 3 == 0)
              Thread.sleep(5)
          } else {
            // around 200 / s
            Thread.sleep(5)
          }
        }
      }
      ref ! akka.actor.Status.Success("done")

      Await.result(done, 20.seconds) should ===(Done)
    }

  }
}
