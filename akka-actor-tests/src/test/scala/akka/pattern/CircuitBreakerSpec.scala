/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import akka.actor.ActorSystem
import language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future, TimeoutException }
import scala.util.{ Try, Success, Failure }
import akka.testkit._
import org.mockito.ArgumentCaptor
import org.scalatest.BeforeAndAfter
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._

object CircuitBreakerSpec {

  class TestException extends RuntimeException

  class Breaker(val instance: CircuitBreaker)(implicit system: ActorSystem) extends MockitoSugar {
    val halfOpenLatch = new TestLatch(1)
    val openLatch = new TestLatch(1)
    val closedLatch = new TestLatch(1)
    val callSuccessLatch = new TestLatch(1)
    val callFailureLatch = new TestLatch(1)
    val callTimeoutLatch = new TestLatch(1)
    val callBreakerOpenLatch = new TestLatch(1)

    val callSuccessConsumerMock = mock[Long ⇒ Unit]
    val callFailureConsumerMock = mock[Long ⇒ Unit]
    val callTimeoutConsumerMock = mock[Long ⇒ Unit]

    def apply(): CircuitBreaker = instance
    instance
      .onClose(closedLatch.countDown())
      .onHalfOpen(halfOpenLatch.countDown())
      .onOpen(openLatch.countDown())
      .onCallSuccess(value ⇒ {
        callSuccessConsumerMock(value)
        callSuccessLatch.countDown()
      })
      .onCallFailure(value ⇒ {
        callFailureConsumerMock(value)
        callFailureLatch.countDown()
      })
      .onCallTimeout(value ⇒ {
        callTimeoutConsumerMock(value)
        callTimeoutLatch.countDown()
      })
      .onCallBreakerOpen(callBreakerOpenLatch.countDown())
  }

  val shortCallTimeout = 50.millis
  def shortCallTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, shortCallTimeout, 500.millis.dilated))

  val shortResetTimeout = 50.millis
  def shortResetTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 1000.millis.dilated, shortResetTimeout))

  def longCallTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 5 seconds, 500.millis.dilated))

  val longResetTimeout = 5.seconds
  def longResetTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 100.millis.dilated, longResetTimeout))

  def multiFailureCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 5, 200.millis.dilated, 500.millis.dilated))

  def nonOneFactorCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 2000.millis.dilated, 1000.millis.dilated, 1.day.dilated, 5))

  val evenNumberIsFailure: Try[Int] ⇒ Boolean = {
    case Success(i) ⇒ i % 2 == 0
    case _          ⇒ true
  }
}

class CircuitBreakerSpec extends AkkaSpec with BeforeAndAfter with MockitoSugar {
  import CircuitBreakerSpec.TestException
  implicit def ec = system.dispatcher
  implicit def s = system

  val awaitTimeout = 2.seconds.dilated

  def checkLatch(latch: TestLatch): Unit = Await.ready(latch, awaitTimeout)

  def throwException = throw new TestException

  def sayHi = "hi"

  def timeCaptor = ArgumentCaptor.forClass(classOf[Long])

  "A synchronous circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }

      checkLatch(breaker.openLatch)

      val e = intercept[CircuitBreakerOpenException] { breaker().withSyncCircuitBreaker(sayHi) }
      (e.remainingDuration > Duration.Zero) should ===(true)
      (e.remainingDuration <= CircuitBreakerSpec.longResetTimeout) should ===(true)
    }

    "transition to half-open on reset timeout" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
    }

    "still be in open state after calling success method" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
      breaker().succeed()
      breaker().isOpen should ===(true)
    }

    "still be in open state after calling fail method" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
      breaker().fail()
      breaker().isOpen should ===(true)
    }

    "invoke onHalfOpen during transition to half-open state" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
    }

    "invoke onCallBreakerOpen when called before reset timeout" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
      intercept[CircuitBreakerOpenException] { breaker().withSyncCircuitBreaker(sayHi) }
      checkLatch(breaker.callBreakerOpenLatch)
    }

    "invoke onCallFailure when call results in exception" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()
      val captor = timeCaptor

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.callFailureLatch)

      verify(breaker.callFailureConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.longResetTimeout.toNanos should ===(true)
    }
  }

  "A synchronous circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
      assert("hi" == breaker().withSyncCircuitBreaker(sayHi))
      checkLatch(breaker.closedLatch)
    }

    "pass through next call and close on exception" when {
      "exception is defined as call succeeded" in {
        val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
        intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
        checkLatch(breaker.halfOpenLatch)

        val allReturnIsSuccess: Try[String] ⇒ Boolean = _ ⇒ false

        intercept[TestException] { breaker().withSyncCircuitBreaker(throwException, allReturnIsSuccess) }
        checkLatch(breaker.closedLatch)
      }
    }

    "open on exception in call" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
      breaker.openLatch.reset
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
    }

    "open on even number" when {
      "even number is defined as failure" in {
        val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
        intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
        checkLatch(breaker.halfOpenLatch)
        breaker.openLatch.reset
        breaker().withSyncCircuitBreaker(2, CircuitBreakerSpec.evenNumberIsFailure)
        checkLatch(breaker.openLatch)
      }
    }

    "open on calling fail method" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
      breaker.openLatch.reset
      breaker().fail()
      checkLatch(breaker.openLatch)
    }

    "close on calling success method" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
      breaker().succeed()
      checkLatch(breaker.closedLatch)
    }

    "pass through next call and invoke onCallSuccess on success" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      val captor = timeCaptor

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)

      breaker().withSyncCircuitBreaker(sayHi)
      checkLatch(breaker.callSuccessLatch)

      verify(breaker.callSuccessConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.shortResetTimeout.toNanos should ===(true)
    }

    "pass through next call and invoke onCallFailure on failure" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      val captor = timeCaptor

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
      checkLatch(breaker.callFailureLatch)

      breaker.callFailureLatch.reset()

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.callFailureLatch)

      verify(breaker.callFailureConsumerMock, times(2))(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.shortResetTimeout.toNanos should ===(true)
    }

    "pass through next call and invoke onCallTimeout on timeout" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      val captor = timeCaptor

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)

      intercept[TimeoutException] { breaker().withSyncCircuitBreaker(Thread.sleep(200.millis.dilated.toMillis)) }
      checkLatch(breaker.callTimeoutLatch)

      verify(breaker.callTimeoutConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < (CircuitBreakerSpec.shortCallTimeout * 2).dilated.toNanos should ===(true)
    }

    "pass through next call and invoke onCallBreakerOpen while executing other" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)

      breaker().withCircuitBreaker(Future(Thread.sleep(250.millis.dilated.toMillis)))
      intercept[CircuitBreakerOpenException] { breaker().withSyncCircuitBreaker(sayHi) }

      checkLatch(breaker.callBreakerOpenLatch)
    }

    "pass through next call and invoke onCallSuccess after transition to open state" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)

      breaker().withSyncCircuitBreaker(Future.successful(sayHi))
      checkLatch(breaker.callSuccessLatch)
    }
  }

  "A synchronous circuit breaker that is closed" must {
    "allow calls through" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      breaker().withSyncCircuitBreaker(sayHi) should ===("hi")
    }

    "increment failure count on failure" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      breaker().currentFailureCount should ===(0)
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount should ===(1)
    }

    "increment failure count on even number" when {
      "even number is considered failure" in {
        val breaker = CircuitBreakerSpec.longCallTimeoutCb()
        breaker().currentFailureCount should ===(0)
        val result = breaker().withSyncCircuitBreaker(2, CircuitBreakerSpec.evenNumberIsFailure)
        checkLatch(breaker.openLatch)

        breaker().currentFailureCount should ===(1)
        result should ===(2)
      }
    }

    "increment failure count on fail method" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      breaker().currentFailureCount should ===(0)
      breaker().fail()
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount should ===(1)
    }

    "reset failure count after success" in {
      val breaker = CircuitBreakerSpec.multiFailureCb()
      breaker().currentFailureCount should ===(0)
      intercept[TestException] {
        val ct = Thread.currentThread() // Ensure that the thunk is executed in the tests thread
        breaker().withSyncCircuitBreaker({ if (Thread.currentThread() eq ct) throwException else "fail" })
      }
      breaker().currentFailureCount should ===(1)
      breaker().withSyncCircuitBreaker(sayHi)
      breaker().currentFailureCount should ===(0)
    }

    "reset failure count after exception in call" when {
      "exception is defined as Success" in {
        val breaker = CircuitBreakerSpec.multiFailureCb()
        breaker().currentFailureCount should ===(0)
        intercept[TestException] {
          val ct = Thread.currentThread() // Ensure that the thunk is executed in the tests thread
          breaker().withSyncCircuitBreaker({ if (Thread.currentThread() eq ct) throwException else "fail" })
        }
        breaker().currentFailureCount should ===(1)

        val harmlessException = new TestException
        val harmlessExceptionAsSuccess: Try[String] ⇒ Boolean = {
          case Success(_)  ⇒ false
          case Failure(ex) ⇒ ex != harmlessException
        }

        intercept[TestException] {
          breaker().withSyncCircuitBreaker(throw harmlessException, harmlessExceptionAsSuccess)
        }

        breaker().currentFailureCount should ===(0)
      }
    }

    "reset failure count after success method" in {
      val breaker = CircuitBreakerSpec.multiFailureCb()
      breaker().currentFailureCount should ===(0)
      intercept[TestException] {
        val ct = Thread.currentThread() // Ensure that the thunk is executed in the tests thread
        breaker().withSyncCircuitBreaker({ if (Thread.currentThread() eq ct) throwException else "fail" })
      }
      breaker().currentFailureCount should ===(1)
      breaker().succeed()
      breaker().currentFailureCount should ===(0)
    }

    "throw TimeoutException on callTimeout" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      intercept[TimeoutException] {
        breaker().withSyncCircuitBreaker {
          Thread.sleep(200.millis.dilated.toMillis)
        }
      }
      breaker().currentFailureCount should ===(1)
    }

    "increment failure count on callTimeout before call finishes" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      Future {
        breaker().withSyncCircuitBreaker {
          Thread.sleep(1.second.dilated.toMillis)
        }
      }
      within(900.millis) {
        awaitCond(breaker().currentFailureCount == 1, 100.millis.dilated)
      }
    }

    "invoke onCallSuccess if call succeeds" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      val captor = timeCaptor

      breaker().withSyncCircuitBreaker(sayHi)
      checkLatch(breaker.callSuccessLatch)

      verify(breaker.callSuccessConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.shortCallTimeout.toNanos should ===(true)
    }

    "invoke onCallTimeout if call timeouts" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      val captor = timeCaptor

      intercept[TimeoutException](breaker().withSyncCircuitBreaker(Thread.sleep(250.millis.dilated.toMillis)))
      checkLatch(breaker.callTimeoutLatch)

      verify(breaker.callTimeoutConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < (CircuitBreakerSpec.shortCallTimeout * 2).toNanos should ===(true)
    }

    "invoke onCallFailure if call fails" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      val captor = timeCaptor

      intercept[TestException](breaker().withSyncCircuitBreaker(throwException))
      checkLatch(breaker.callFailureLatch)

      verify(breaker.callFailureConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.shortCallTimeout.toNanos should ===(true)
    }

    "invoke onOpen if call fails and breaker transits to open state" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()

      intercept[TestException](breaker().withSyncCircuitBreaker(throwException))
      checkLatch(breaker.openLatch)
    }
  }

  "An asynchronous circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))

      checkLatch(breaker.openLatch)

      intercept[CircuitBreakerOpenException] { Await.result(breaker().withCircuitBreaker(Future(sayHi)), awaitTimeout) }
    }

    "transition to half-open on reset timeout" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)
    }

    "increase the reset timeout after it transits to open again" in {
      val breaker = CircuitBreakerSpec.nonOneFactorCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)

      val e1 = intercept[CircuitBreakerOpenException] { breaker().withSyncCircuitBreaker(sayHi) }
      val shortRemainingDuration = e1.remainingDuration

      Thread.sleep(1000.millis.dilated.toMillis)
      checkLatch(breaker.halfOpenLatch)

      // transit to open again
      breaker.openLatch.reset
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)

      val e2 = intercept[CircuitBreakerOpenException] { breaker().withSyncCircuitBreaker(sayHi) }
      val longRemainingDuration = e2.remainingDuration

      (shortRemainingDuration < longRemainingDuration) should ===(true)

    }

    "invoke onHalfOpen during transition to half-open state" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()

      intercept[TestException] { Await.result(breaker().withCircuitBreaker(Future(throwException)), awaitTimeout) }
      checkLatch(breaker.halfOpenLatch)
    }

    "invoke onCallBreakerOpen when called before reset timeout" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)

      breaker().withCircuitBreaker(Future(sayHi))
      checkLatch(breaker.callBreakerOpenLatch)
    }

    "invoke onCallFailure when call results in exception" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()
      val captor = timeCaptor

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.callFailureLatch)

      verify(breaker.callFailureConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.longResetTimeout.toNanos should ===(true)
    }
  }

  "An asynchronous circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)
      Await.result(breaker().withCircuitBreaker(Future(sayHi)), awaitTimeout) should ===("hi")
      checkLatch(breaker.closedLatch)
    }

    "pass through next call and close on exception" when {
      "exception is defined as call succeeded" in {
        val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
        breaker().withCircuitBreaker(Future(throwException))
        checkLatch(breaker.halfOpenLatch)
        val allReturnIsSuccess: Try[String] ⇒ Boolean = _ ⇒ false
        Await.ready(breaker().withCircuitBreaker(Future(throwException), allReturnIsSuccess), awaitTimeout)
        checkLatch(breaker.closedLatch)
      }
    }

    "re-open on exception in call" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)
      breaker.openLatch.reset
      intercept[TestException] { Await.result(breaker().withCircuitBreaker(Future(throwException)), awaitTimeout) }
      checkLatch(breaker.openLatch)
    }

    "re-open on even number" when {
      "even number is defined as failure" in {
        val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
        intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
        checkLatch(breaker.halfOpenLatch)
        breaker.openLatch.reset
        Await.result(breaker().withCircuitBreaker(Future(2), CircuitBreakerSpec.evenNumberIsFailure), awaitTimeout)
        checkLatch(breaker.openLatch)
      }
    }

    "re-open on async failure" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)

      breaker.openLatch.reset
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)
    }

    "pass through next call and invoke onCallSuccess on success" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      val captor = timeCaptor

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)

      breaker().withCircuitBreaker(Future(sayHi))
      checkLatch(breaker.callSuccessLatch)

      verify(breaker.callSuccessConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.shortResetTimeout.toNanos should ===(true)
    }

    "pass through next call and invoke onCallFailure on failure" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      val captor = timeCaptor

      breaker().withCircuitBreaker(Future(throwException))

      checkLatch(breaker.halfOpenLatch)
      checkLatch(breaker.callFailureLatch)
      breaker.callFailureLatch.reset()

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.callFailureLatch)

      verify(breaker.callFailureConsumerMock, times(2))(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.shortResetTimeout.toNanos should ===(true)
    }

    "pass through next call and invoke onCallTimeout on timeout" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      val captor = timeCaptor

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)

      breaker().withCircuitBreaker(Future(Thread.sleep(200.millis.dilated.toMillis)))
      checkLatch(breaker.callTimeoutLatch)

      verify(breaker.callTimeoutConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < (CircuitBreakerSpec.shortCallTimeout * 2).dilated.toNanos should ===(true)
    }

    "pass through next call and invoke onCallBreakerOpen while executing other" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      val captor = timeCaptor

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)

      breaker().withCircuitBreaker(Future(Thread.sleep(250.millis.dilated.toMillis)))
      breaker().withCircuitBreaker(Future(sayHi))
      checkLatch(breaker.callBreakerOpenLatch)
    }

    "pass through next call and invoke onOpen after transition to open state" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)

      breaker().withCircuitBreaker(Future(sayHi))
      checkLatch(breaker.callSuccessLatch)
    }
  }

  "An asynchronous circuit breaker that is closed" must {
    "allow calls through" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      Await.result(breaker().withCircuitBreaker(Future(sayHi)), awaitTimeout) should ===("hi")
    }

    "increment failure count on exception" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      intercept[TestException] { Await.result(breaker().withCircuitBreaker(Future(throwException)), awaitTimeout) }
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount should ===(1)
    }

    "increment failure count on even number" when {
      "even number is considered failure" in {
        val breaker = CircuitBreakerSpec.longCallTimeoutCb()
        breaker().currentFailureCount should ===(0)
        val result = Await.result(breaker().withCircuitBreaker(Future(2), CircuitBreakerSpec.evenNumberIsFailure), awaitTimeout)
        checkLatch(breaker.openLatch)
        breaker().currentFailureCount should ===(1)
        result should ===(2)
      }
    }

    "increment failure count on async failure" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount should ===(1)
    }

    "reset failure count after success" in {
      val breaker = CircuitBreakerSpec.multiFailureCb()
      breaker().withCircuitBreaker(Future(sayHi))
      for (_ ← 1 to 4) breaker().withCircuitBreaker(Future(throwException))
      awaitCond(breaker().currentFailureCount == 4, awaitTimeout)
      breaker().withCircuitBreaker(Future(sayHi))
      awaitCond(breaker().currentFailureCount == 0, awaitTimeout)
    }

    "reset failure count after exception in call" when {
      "exception is defined as Success" in {
        val breaker: CircuitBreakerSpec.Breaker = CircuitBreakerSpec.multiFailureCb()

        for (_ ← 1 to 4) breaker().withCircuitBreaker(Future(throwException))
        awaitCond(breaker().currentFailureCount == 4, awaitTimeout, message = s"Current failure count: ${breaker().currentFailureCount}")

        val harmlessException = new TestException
        val harmlessExceptionAsSuccess: Try[String] ⇒ Boolean = {
          case Success(_)  ⇒ false
          case Failure(ex) ⇒ ex != harmlessException
        }

        breaker().withCircuitBreaker(Future(throw harmlessException), harmlessExceptionAsSuccess)
        awaitCond(breaker().currentFailureCount == 0, awaitTimeout)
      }
    }

    "increment failure count on callTimeout" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()

      val fut = breaker().withCircuitBreaker(Future {
        Thread.sleep(150.millis.dilated.toMillis)
        throwException
      })
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount should ===(1)
      // Since the timeout should have happened before the inner code finishes
      // we expect a timeout, not TestException
      intercept[TimeoutException] {
        Await.result(fut, awaitTimeout)
      }

    }

    "invoke onCallSuccess if call succeeds" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      val captor = timeCaptor

      breaker().withCircuitBreaker(Future(sayHi))
      checkLatch(breaker.callSuccessLatch)

      verify(breaker.callSuccessConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.shortCallTimeout.toNanos should ===(true)
    }

    "invoke onCallTimeout if call timeouts" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      val captor = timeCaptor

      breaker().withCircuitBreaker(Future(Thread.sleep(250.millis.dilated.toMillis)))
      checkLatch(breaker.callTimeoutLatch)

      verify(breaker.callTimeoutConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < (CircuitBreakerSpec.shortCallTimeout * 2).toNanos should ===(true)
    }

    "invoke onCallFailure if call fails" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      val captor = timeCaptor

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.callFailureLatch)

      verify(breaker.callFailureConsumerMock)(captor.capture())
      captor.getValue > 0 should ===(true)
      captor.getValue < CircuitBreakerSpec.shortCallTimeout.toNanos should ===(true)
    }

    "invoke onOpen if call fails and breaker transits to open state" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)
    }
  }
}
