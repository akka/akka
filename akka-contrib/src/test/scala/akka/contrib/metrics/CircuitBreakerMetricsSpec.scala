package akka.contrib.metrics

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.Done
import akka.pattern.CircuitBreaker
import akka.stream.scaladsl.Sink
import akka.stream.testkit.TestSubscriber
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.testkit.AkkaSpec

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object CircuitBreakerMetricsSpec {

  object TestException extends RuntimeException

  def delayedAction(implicit ec: ExecutionContext) = Future {
    Thread.sleep(100)
    Done
  }

  implicit class LatchExt(value: CountDownLatch) {
    def checkReady = if (!value.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Expected latch to be zero")
  }

}

class CircuitBreakerMetricsSpec extends AkkaSpec {

  import CircuitBreakerMetricsSpec._
  import system.dispatcher
  implicit val mat = ActorMaterializer()

  "CircuitBreakerMetrics`s events" must {
    "return proper events for successful invocations" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(Done))
      breaker.withCircuitBreaker(Future(Done))
      breaker.withCircuitBreaker(Future(Done))

      sub.request(3)

      s.expectNextPF { case e: Event.CallSuccess => true } shouldBe true
      s.expectNextPF { case e: Event.CallSuccess => true } shouldBe true
      s.expectNextPF { case e: Event.CallSuccess => true } shouldBe true

      sub.cancel()
    }

    "return proper events for failed invocations" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(throw TestException))
      breaker.withCircuitBreaker(Future(throw TestException))
      breaker.withCircuitBreaker(Future(throw TestException))

      sub.request(3)

      s.expectNextPF { case _: Event.CallFailure => true } shouldBe true
      s.expectNextPF { case _: Event.CallFailure => true } shouldBe true
      s.expectNextPF { case _: Event.CallFailure => true } shouldBe true

      sub.cancel()
    }

    "return proper events for timeout invocations" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 50 millis, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(delayedAction)
      breaker.withCircuitBreaker(delayedAction)
      breaker.withCircuitBreaker(delayedAction)

      sub.request(3)
      s.expectNextPF { case _: Event.CallTimeout => true } shouldBe true
      s.expectNextPF { case _: Event.CallTimeout => true } shouldBe true
      s.expectNextPF { case _: Event.CallTimeout => true } shouldBe true

      sub.cancel()
    }

    "return proper events for breaker open invocations" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 1, 3 seconds, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(throw TestException))

      sub.request(2)
      s.expectNextPF { case _: Event.CallFailure => true } shouldBe true
      s.expectNextPF { case _: Event.BreakerOpened => true } shouldBe true

      breaker.withCircuitBreaker(Future(Done))
      breaker.withCircuitBreaker(Future(Done))
      breaker.withCircuitBreaker(Future(Done))

      sub.request(3)
      s.expectNextPF { case _: Event.CallBreakerOpen => true } shouldBe true
      s.expectNextPF { case _: Event.CallBreakerOpen => true } shouldBe true
      s.expectNextPF { case _: Event.CallBreakerOpen => true } shouldBe true

      sub.cancel()
    }

    "return events for breaker state changes" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 1, 3 seconds, 250 millis)
      CircuitBreakerMetrics.events(breaker, buffer = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(throw TestException))

      sub.request(2)
      s.expectNextPF { case _: Event.BreakerOpened | _: Event.CallFailure => true } shouldBe true
      s.expectNextPF { case _: Event.BreakerOpened | _: Event.CallFailure => true } shouldBe true

      breaker.withCircuitBreaker(Future(Done))
      sub.request(1)
      s.expectNextPF { case _: Event.CallBreakerOpen => true } shouldBe true

      sub.request(1)
      s.expectNextPF { case _: Event.BreakerHalfOpened => true } shouldBe true

      breaker.withCircuitBreaker(Future(Done))
      sub.request(2)
      s.expectNextPF { case _: Event.CallSuccess => true } shouldBe true
      s.expectNextPF { case _: Event.BreakerOpened => true } shouldBe true

      breaker.withCircuitBreaker(Future(Done))
      breaker.withCircuitBreaker(Future(Done))
      sub.request(2)

      s.expectNextPF { case _: Event.CallSuccess => true } shouldBe true
      s.expectNextPF { case _: Event.CallSuccess => true } shouldBe true

      sub.cancel()
    }


    "buffer when needed" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 100).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      for (n ← 1 to 20) breaker.withCircuitBreaker(Future(Done))
      sub.request(10)
      for (n ← 1 to 10) breaker.withCircuitBreaker(Future(Done))
      sub.request(10)
      for (n ← 11 to 20) breaker.withCircuitBreaker(Future(Done))

      for (n ← 200 to 399) breaker.withCircuitBreaker(Future(Done))
      sub.request(100)
      for (n ← 300 to 399) breaker.withCircuitBreaker(Future(Done))

      sub.cancel()
    }

    "not fail when 0 buffer space and demand is signalled" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 0).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      sub.request(1)
      breaker.withCircuitBreaker(Future(Done))
      s.expectNext()

      sub.cancel()
    }

    "fail stream on buffer overflow in fail mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 1, overflowStrategy = OverflowStrategy.fail).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(Done))
      breaker.withCircuitBreaker(Future(Done))

      s.expectError()

      sub.cancel()
    }

    "drop new event in dropNew mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val latch = new CountDownLatch(2)
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 1, overflowStrategy = OverflowStrategy.dropNew).to(Sink.fromSubscriber(s)).run()
      breaker.onCallSuccess(_ => latch.countDown())

      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(Done))
      breaker.withCircuitBreaker(Future(Done))
      latch.checkReady

      sub.request(1)
      s.expectNextPF { case _: Event.CallSuccess => true } shouldBe true

      sub.request(1)
      breaker.withCircuitBreaker(Future(throw TestException))
      s.expectNextPF { case _: Event.CallFailure => true } shouldBe true

      sub.cancel()
    }

    "drop event from head in dropHead mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      val latchFailure = new CountDownLatch(2)
      val latchSuccess = new CountDownLatch(1)
      CircuitBreakerMetrics.events(breaker, buffer = 2, overflowStrategy = OverflowStrategy.dropHead).to(Sink.fromSubscriber(s)).run()
      breaker.onCallFailure(_ => latchFailure.countDown())
      breaker.onCallSuccess(_ => latchSuccess.countDown())

      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(throw TestException))
      breaker.withCircuitBreaker(Future(throw TestException))
      latchFailure.checkReady
      breaker.withCircuitBreaker(Future(Done))
      latchSuccess.checkReady

      sub.request(2)
      s.expectNextPF { case _: Event.CallFailure => true } shouldBe true
      s.expectNextPF { case _: Event.CallSuccess => true } shouldBe true

      sub.request(1)
      breaker.withCircuitBreaker(Future(Done))
      s.expectNextPF { case _: Event.CallSuccess => true } shouldBe true

      sub.cancel()
    }

    "drop event from tail in dropTail mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      val latchFailure = new CountDownLatch(2)
      val latchSuccess = new CountDownLatch(1)
      CircuitBreakerMetrics.events(breaker, buffer = 2, overflowStrategy = OverflowStrategy.dropTail).to(Sink.fromSubscriber(s)).run()
      breaker.onCallFailure(_ => latchFailure.countDown())
      breaker.onCallSuccess(_ => latchSuccess.countDown())

      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(throw TestException))
      breaker.withCircuitBreaker(Future(throw TestException))
      latchFailure.checkReady
      breaker.withCircuitBreaker(Future(Done))
      latchSuccess.checkReady

      sub.request(2)
      s.expectNextPF { case _: Event.CallFailure => true } shouldBe true
      s.expectNextPF { case _: Event.CallSuccess => true } shouldBe true

      sub.request(1)
      breaker.withCircuitBreaker(Future(throw TestException))
      s.expectNextPF { case _: Event.CallFailure => true } shouldBe true

      sub.cancel()
    }

    "clear buffer if in DropBuffer mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      val latchFailure = new CountDownLatch(3)
      val latchSuccess = new CountDownLatch(1)
      CircuitBreakerMetrics.events(breaker, buffer = 3, overflowStrategy = OverflowStrategy.dropBuffer).to(Sink.fromSubscriber(s)).run()
      breaker.onCallFailure(_ => latchFailure.countDown())
      breaker.onCallSuccess(_ => latchSuccess.countDown())

      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(throw TestException))
      breaker.withCircuitBreaker(Future(throw TestException))
      breaker.withCircuitBreaker(Future(throw TestException))
      latchFailure.checkReady
      breaker.withCircuitBreaker(Future(Done))
      latchSuccess.checkReady

      sub.request(1)
      s.expectNextPF { case _: Event.CallSuccess => true } shouldBe true

      sub.cancel()
    }

    "fail if in BackPressure mode" in {
      val s = TestSubscriber.manualProbe[Event]()
      val breaker = new CircuitBreaker(system.scheduler, 5, 3 seconds, 5 seconds)
      CircuitBreakerMetrics.events(breaker, buffer = 1, overflowStrategy = OverflowStrategy.backpressure).to(Sink.fromSubscriber(s)).run()
      val sub = s.expectSubscription

      breaker.withCircuitBreaker(Future(Done))
      breaker.withCircuitBreaker(Future(Done))

      s.expectError()
      sub.cancel()
    }
  }
}
