/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import language.postfixOps

import scala.concurrent.duration._
import akka.testkit._
import org.scalatest.BeforeAndAfter
import akka.actor.{ ActorSystem, Scheduler }
import concurrent.{ ExecutionContext, Future, Await }

object CircuitBreakerSpec {

  class TestException extends RuntimeException

  class Breaker(val instance: CircuitBreaker)(implicit system: ActorSystem) {
    val halfOpenLatch = new TestLatch(1)
    val openLatch = new TestLatch(1)
    val closedLatch = new TestLatch(1)
    def apply(): CircuitBreaker = instance
    instance.onClose(closedLatch.countDown()).onHalfOpen(halfOpenLatch.countDown()).onOpen(openLatch.countDown())
  }

  def shortCallTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 50.millis.dilated, 500.millis.dilated))

  def shortResetTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 1000.millis.dilated, 50.millis.dilated))

  def longCallTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 5 seconds, 500.millis.dilated))

  def longResetTimeoutCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 1, 100.millis.dilated, 5 seconds))

  def multiFailureCb()(implicit system: ActorSystem, ec: ExecutionContext): Breaker =
    new Breaker(new CircuitBreaker(system.scheduler, 5, 200.millis.dilated, 500.millis.dilated))
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CircuitBreakerSpec extends AkkaSpec with BeforeAndAfter {
  import CircuitBreakerSpec.TestException
  implicit def ec = system.dispatcher
  implicit def s = system

  val awaitTimeout = 2.seconds.dilated

  def checkLatch(latch: TestLatch): Unit = Await.ready(latch, awaitTimeout)

  def throwException = throw new TestException

  def sayHi = "hi"

  "A synchronous circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {
      val breaker = CircuitBreakerSpec.longResetTimeoutCb()

      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }

      checkLatch(breaker.openLatch)

      intercept[CircuitBreakerOpenException] { breaker().withSyncCircuitBreaker(sayHi) }
    }

    "transition to half-open on reset timeout" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
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

    "open on exception in call" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.halfOpenLatch)
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
    }
  }

  "A synchronous circuit breaker that is closed" must {
    "allow calls through" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      breaker().withSyncCircuitBreaker(sayHi) must be("hi")
    }

    "increment failure count on failure" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      breaker().currentFailureCount must be(0)
      intercept[TestException] { breaker().withSyncCircuitBreaker(throwException) }
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount must be(1)
    }

    "reset failure count after success" in {
      val breaker = CircuitBreakerSpec.multiFailureCb()
      breaker().currentFailureCount must be(0)
      intercept[TestException] {
        val ct = Thread.currentThread() // Ensure that the thunk is executed in the tests thread
        breaker().withSyncCircuitBreaker({ if (Thread.currentThread() eq ct) throwException else "fail" })
      }
      breaker().currentFailureCount must be === 1
      breaker().withSyncCircuitBreaker(sayHi)
      breaker().currentFailureCount must be === 0
    }

    "increment failure count on callTimeout" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      breaker().withSyncCircuitBreaker(Thread.sleep(100.millis.dilated.toMillis))
      awaitCond(breaker().currentFailureCount == 1, remaining)
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
  }

  "An asynchronous circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)
      Await.result(breaker().withCircuitBreaker(Future(sayHi)), awaitTimeout) must be("hi")
      checkLatch(breaker.closedLatch)
    }

    "re-open on exception in call" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)
      intercept[TestException] { Await.result(breaker().withCircuitBreaker(Future(throwException)), awaitTimeout) }
      checkLatch(breaker.openLatch)
    }

    "re-open on async failure" in {
      val breaker = CircuitBreakerSpec.shortResetTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.halfOpenLatch)

      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)
    }
  }

  "An asynchronous circuit breaker that is closed" must {
    "allow calls through" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      Await.result(breaker().withCircuitBreaker(Future(sayHi)), awaitTimeout) must be("hi")
    }

    "increment failure count on exception" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      intercept[TestException] { Await.result(breaker().withCircuitBreaker(Future(throwException)), awaitTimeout) }
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount must be(1)
    }

    "increment failure count on async failure" in {
      val breaker = CircuitBreakerSpec.longCallTimeoutCb()
      breaker().withCircuitBreaker(Future(throwException))
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount must be(1)
    }

    "reset failure count after success" in {
      val breaker = CircuitBreakerSpec.multiFailureCb()
      breaker().withCircuitBreaker(Future(sayHi))
      for (n ← 1 to 4) breaker().withCircuitBreaker(Future(throwException))
      awaitCond(breaker().currentFailureCount == 4, awaitTimeout)
      breaker().withCircuitBreaker(Future(sayHi))
      awaitCond(breaker().currentFailureCount == 0, awaitTimeout)
    }

    "increment failure count on callTimeout" in {
      val breaker = CircuitBreakerSpec.shortCallTimeoutCb()
      breaker().withCircuitBreaker(Future { Thread.sleep(100.millis.dilated.toMillis); sayHi })
      checkLatch(breaker.openLatch)
      breaker().currentFailureCount must be(1)
    }
  }

}
