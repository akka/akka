package akka.contrib.metrics

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.Done
import akka.pattern.CircuitBreaker
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.testkit.TestSubscriber
import akka.stream.{ ActorMaterializer, KillSwitches, OverflowStrategy }
import akka.testkit.AkkaSpec

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

object CircuitBreakerMetricsSpec {

  object TestException extends RuntimeException

  def delayedSuccess(implicit ec: ExecutionContext) = Future {
    Thread.sleep(200)
    Done
  }

  def delayedFailure(implicit ec: ExecutionContext) = Future {
    Thread.sleep(200)
    throw TestException
  }

  implicit class LatchExt(value: CountDownLatch) {
    def checkReady() = if (!value.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Expected latch to be zero")
  }

}

class CircuitBreakerMetricsSpec extends AkkaSpec {

  import CircuitBreakerMetricsSpec._
  import system.dispatcher

  implicit val mat = ActorMaterializer()

  "CircuitBreakerMetrics`s events" must {
    "return proper events for successful invocations" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription()

      breaker.withCircuitBreaker(delayedSuccess)
      breaker.withCircuitBreaker(delayedSuccess)
      breaker.withCircuitBreaker(delayedSuccess)

      sub.request(3)
      s.expectNext() shouldBe a[Event.CallSuccess]
      s.expectNext() shouldBe a[Event.CallSuccess]
      s.expectNext() shouldBe a[Event.CallSuccess]

      sub.cancel()
    }

    "return proper events for failed invocations" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(delayedFailure)
      breaker.withCircuitBreaker(delayedFailure)
      breaker.withCircuitBreaker(delayedFailure)

      sub.request(3)
      s.expectNext() shouldBe a[Event.CallFailure]
      s.expectNext() shouldBe a[Event.CallFailure]
      s.expectNext() shouldBe a[Event.CallFailure]

      sub.cancel()
    }

    "return proper events for timeout invocations" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 50.millis, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(delayedSuccess)
      breaker.withCircuitBreaker(delayedSuccess)
      breaker.withCircuitBreaker(delayedSuccess)

      sub.request(3)
      s.expectNext() shouldBe a[Event.CallTimeout]
      s.expectNext() shouldBe a[Event.CallTimeout]
      s.expectNext() shouldBe a[Event.CallTimeout]

      sub.cancel()
    }

    "return proper events for breaker open invocations" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 1, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(delayedFailure)

      sub.request(2)
      s.expectNext() shouldBe a[Event.CallFailure]
      s.expectNext() shouldBe a[Event.BreakerOpened]

      breaker.withCircuitBreaker(Future.successful(Done))
      breaker.withCircuitBreaker(Future.successful(Done))
      breaker.withCircuitBreaker(Future.successful(Done))

      sub.request(3)
      s.expectNext() shouldBe a[Event.CallBreakerOpen]
      s.expectNext() shouldBe a[Event.CallBreakerOpen]
      s.expectNext() shouldBe a[Event.CallBreakerOpen]

      sub.cancel()
    }

    "return events for breaker state changes" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 1, 3.seconds, 250.millis)
      CircuitBreakerMetrics.events(breaker, bufferSize = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future.failed(TestException))

      sub.request(2)
      s.expectNext() should (be(a[Event.BreakerOpened]) or be(a[Event.CallFailure]))
      s.expectNext() should (be(a[Event.BreakerOpened]) or be(a[Event.CallFailure]))

      breaker.withCircuitBreaker(Future.successful(Done))
      sub.request(1)
      s.expectNext() shouldBe a[Event.CallBreakerOpen]

      sub.request(1)
      s.expectNext() shouldBe a[Event.BreakerHalfOpened]

      breaker.withCircuitBreaker(Future.successful(Done))
      sub.request(2)
      s.expectNext() should (be(a[Event.CallSuccess]) or be(a[Event.BreakerClosed]))
      s.expectNext() should (be(a[Event.CallSuccess]) or be(a[Event.BreakerClosed]))

      breaker.withCircuitBreaker(Future.successful(Done))
      breaker.withCircuitBreaker(Future.successful(Done))
      sub.request(2)

      s.expectNext() shouldBe a[Event.CallSuccess]
      s.expectNext() shouldBe a[Event.CallSuccess]

      sub.cancel()
    }

    "buffer when needed" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      for (n ← 1 to 20) breaker.withCircuitBreaker(Future.successful(Done))
      sub.request(10)
      for (n ← 1 to 10) breaker.withCircuitBreaker(Future.successful(Done))
      sub.request(10)
      for (n ← 11 to 20) breaker.withCircuitBreaker(Future.successful(Done))

      for (n ← 200 to 399) breaker.withCircuitBreaker(Future.successful(Done))
      sub.request(100)
      for (n ← 300 to 399) breaker.withCircuitBreaker(Future.successful(Done))

      sub.cancel()
    }

    "not fail when 0 buffer space and demand is signalled" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 0).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      sub.request(1)
      breaker.withCircuitBreaker(Future.successful(Done))
      s.expectNext()

      sub.cancel()
    }

    "fail stream on buffer overflow in fail mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 1, overflowStrategy = OverflowStrategy.fail).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future.successful(Done))
      breaker.withCircuitBreaker(Future.successful(Done))

      s.expectError()

      sub.cancel()
    }

    "drop new event in dropNew mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val latch = new CountDownLatch(2)
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 1, overflowStrategy = OverflowStrategy.dropNew).to(Sink.fromSubscriber(s)).run()
      breaker.onCallSuccess(_ ⇒ latch.countDown())

      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future.successful(Done))
      breaker.withCircuitBreaker(Future.successful(Done))
      latch.checkReady()

      sub.request(1)
      s.expectNext() shouldBe a[Event.CallSuccess]

      sub.request(1)
      breaker.withCircuitBreaker(Future.failed(TestException))
      s.expectNext() shouldBe a[Event.CallFailure]

      sub.cancel()
    }

    "drop event from head in dropHead mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      val latchFailure = new CountDownLatch(2)
      val latchSuccess = new CountDownLatch(1)
      CircuitBreakerMetrics.events(breaker, bufferSize = 2, overflowStrategy = OverflowStrategy.dropHead).to(Sink.fromSubscriber(s)).run()
      breaker.onCallFailure(_ ⇒ latchFailure.countDown())
      breaker.onCallSuccess(_ ⇒ latchSuccess.countDown())

      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future.failed(TestException))
      breaker.withCircuitBreaker(Future.failed(TestException))
      latchFailure.checkReady()
      breaker.withCircuitBreaker(Future.successful(Done))
      latchSuccess.checkReady()

      sub.request(2)
      s.expectNext() shouldBe a[Event.CallFailure]
      s.expectNext() shouldBe a[Event.CallSuccess]

      sub.request(1)
      breaker.withCircuitBreaker(Future.successful(Done))
      s.expectNext() shouldBe a[Event.CallSuccess]

      sub.cancel()
    }

    "drop event from tail in dropTail mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      val latchFailure = new CountDownLatch(2)
      val latchSuccess = new CountDownLatch(1)
      CircuitBreakerMetrics.events(breaker, bufferSize = 2, overflowStrategy = OverflowStrategy.dropTail).to(Sink.fromSubscriber(s)).run()
      breaker.onCallFailure(_ ⇒ latchFailure.countDown())
      breaker.onCallSuccess(_ ⇒ latchSuccess.countDown())

      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future.failed(TestException))
      breaker.withCircuitBreaker(Future.failed(TestException))
      latchFailure.checkReady()
      breaker.withCircuitBreaker(Future.successful(Done))
      latchSuccess.checkReady()

      sub.request(2)
      s.expectNext() shouldBe a[Event.CallFailure]
      s.expectNext() shouldBe a[Event.CallSuccess]

      sub.request(1)
      breaker.withCircuitBreaker(Future.failed(TestException))
      s.expectNext() shouldBe a[Event.CallFailure]

      sub.cancel()
    }

    "clear buffer if in DropBuffer mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      val latchFailure = new CountDownLatch(3)
      val latchSuccess = new CountDownLatch(1)
      CircuitBreakerMetrics.events(breaker, bufferSize = 3, overflowStrategy = OverflowStrategy.dropBuffer).to(Sink.fromSubscriber(s)).run()
      breaker.onCallFailure(_ ⇒ latchFailure.countDown())
      breaker.onCallSuccess(_ ⇒ latchSuccess.countDown())

      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future.failed(TestException))
      breaker.withCircuitBreaker(Future.failed(TestException))
      breaker.withCircuitBreaker(Future.failed(TestException))
      latchFailure.checkReady()
      breaker.withCircuitBreaker(Future.successful(Done))
      latchSuccess.checkReady()

      sub.request(1)
      s.expectNext() shouldBe a[Event.CallSuccess]

      sub.cancel()
    }

    "fail if in BackPressure mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.events(breaker, bufferSize = 1, overflowStrategy = OverflowStrategy.backpressure).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future.successful(Done))
      breaker.withCircuitBreaker(Future.successful(Done))

      s.expectError()
      sub.cancel()
    }
  }

  "CircuitBreakerMetrics`s timeBuckets" must {
    "return bucket for successful invocations" in {
      val s = TestSubscriber.manualProbe[TimeBucketResult]()
      val breaker = new CircuitBreaker(system.scheduler, 50, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.timeBuckets(breaker, 2.seconds).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription()

      for (n ← 1 to 10) breaker.withCircuitBreaker(delayedSuccess)

      sub.request(1)

      val result = s.expectNext()
      result.callSuccesses.count shouldBe 10
      result.callSuccesses.elapsedTotal should be > 0L
      result.callSuccesses.average should be > 0L

      result.callFailures.count shouldBe 0
      result.callTimeouts.count shouldBe 0
      result.callBreakerOpens.count shouldBe 0

      sub.cancel()
    }

    "return bucket for failed invocations" in {
      val s = TestSubscriber.manualProbe[TimeBucketResult]()
      val breaker = new CircuitBreaker(system.scheduler, 50, 3.seconds, 5.seconds)
      CircuitBreakerMetrics.timeBuckets(breaker, 2.seconds).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription()

      for (n ← 1 to 10) breaker.withCircuitBreaker(delayedFailure)

      sub.request(1)

      val result = s.expectNext()
      result.callFailures.count shouldBe 10
      result.callFailures.elapsedTotal should be > 0L
      result.callFailures.average should be > 0L

      result.callSuccesses.count shouldBe 0
      result.callTimeouts.count shouldBe 0
      result.callBreakerOpens.count shouldBe 0

      sub.cancel()
    }

    "return bucket for timeout invocations" in {
      val s = TestSubscriber.manualProbe[TimeBucketResult]()
      val breaker = new CircuitBreaker(system.scheduler, 50, 50.millis, 5.seconds)
      CircuitBreakerMetrics.timeBuckets(breaker, 2.seconds).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription()

      for (n ← 1 to 10) breaker.withCircuitBreaker(delayedSuccess)

      sub.request(1)

      val result = s.expectNext()
      result.callTimeouts.count shouldBe 10
      result.callTimeouts.elapsedTotal should be > 0L
      result.callTimeouts.average should be > 0L

      result.callSuccesses.count shouldBe 0
      result.callFailures.count shouldBe 0
      result.callBreakerOpens.count shouldBe 0

      sub.cancel()
    }

    "return bucket for breaker open invocations" in {
      val s = TestSubscriber.manualProbe[TimeBucketResult]()
      val breaker = new CircuitBreaker(system.scheduler, 1, 5.seconds, 5.seconds)
      val latch = new CountDownLatch(1)
      CircuitBreakerMetrics.timeBuckets(breaker, 2.seconds).to(Sink.fromSubscriber(s)).run()
      breaker.onOpen(latch.countDown())
      val sub = s.expectSubscription()

      breaker.withCircuitBreaker(Future.failed(TestException))
      latch.checkReady()

      for (n ← 1 to 10) breaker.withCircuitBreaker(Future.successful(Done))

      sub.request(1)

      val result = s.expectNext()
      result.callBreakerOpens.count shouldBe 10
      result.callSuccesses.count shouldBe 0
      result.callFailures.count shouldBe 1
      result.callTimeouts.count shouldBe 0

      sub.cancel()
    }

    "return bucket for breaker state changes" in {
      val s = TestSubscriber.manualProbe[TimeBucketResult]()
      val breaker = new CircuitBreaker(system.scheduler, 1, 5.seconds, 500.millis)
      val openLatch = new CountDownLatch(1)
      val halfOpenLatch = new CountDownLatch(1)
      val closeLatch = new CountDownLatch(1)
      CircuitBreakerMetrics.timeBuckets(breaker, 2.seconds).to(Sink.fromSubscriber(s)).run()
      breaker.onOpen(openLatch.countDown())
      breaker.onHalfOpen(halfOpenLatch.countDown())
      breaker.onClose(closeLatch.countDown())
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future.failed(TestException))
      openLatch.checkReady()
      breaker.withCircuitBreaker(Future.successful(Done))
      halfOpenLatch.checkReady()
      breaker.withCircuitBreaker(Future.successful(Done))
      closeLatch.checkReady()
      breaker.withCircuitBreaker(Future.successful(Done))
      breaker.withCircuitBreaker(Future.successful(Done))

      sub.request(1)

      val result = s.expectNext()

      result.callSuccesses.count shouldBe 3
      result.callFailures.count shouldBe 1
      result.callTimeouts.count shouldBe 0
      result.callBreakerOpens.count shouldBe 1
      result.opens should have size 1
      result.halfOpens should have size 1
      result.closes should have size 1

      sub.cancel()
    }

    "return buckets with proper values" in {
      val breaker = new CircuitBreaker(system.scheduler, 1, 5.seconds, 500.millis)
      val (switch, bucketsFuture) = CircuitBreakerMetrics.timeBuckets(breaker, 100.millis)
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.seq)(Keep.both).run()
      val requests = Source.tick(0.seconds, 10.millis, Done)
        .take(250)
        .mapAsync(1)(value ⇒ breaker.withCircuitBreaker(Future(value)))
        .runWith(Sink.ignore)

      requests.onSuccess {
        case _ ⇒
          Thread sleep 200
          switch.shutdown()
      }

      Await.result(bucketsFuture, 5.seconds)
        .map(_.callSuccesses.count).sum shouldBe 250
    }

    "fail if bufferSize is not greater than 0" in {
      val breaker = new CircuitBreaker(system.scheduler, 5, 1.seconds, 5.seconds)
      val ex = intercept[Exception](CircuitBreakerMetrics.timeBuckets(breaker, 2.seconds, bufferSize = 0))
      ex.getMessage shouldBe "requirement failed: bufferSize must be greater than 0"
    }
  }

}
