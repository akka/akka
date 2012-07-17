/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.pattern

import language.postfixOps

import scala.concurrent.util.duration._
import akka.testkit._
import org.scalatest.BeforeAndAfter
import scala.concurrent.Future
import scala.concurrent.Await

object CircuitBreakerSpec {

  class TestException extends RuntimeException

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CircuitBreakerSpec extends AkkaSpec with BeforeAndAfter {
  implicit val ec = system.dispatcher
  import CircuitBreakerSpec.TestException

  val awaitTimeout = 2.seconds.dilated

  @volatile
  var breakers: TestCircuitBreakers = null

  class TestCircuitBreakers {
    val halfOpenLatch = new TestLatch(1)
    val openLatch = new TestLatch(1)
    val closedLatch = new TestLatch(1)

    val shortCallTimeoutCb = new CircuitBreaker(system.scheduler, 1, 50.millis.dilated, 500.millis.dilated)
      .onClose(closedLatch.countDown())
      .onHalfOpen(halfOpenLatch.countDown())
      .onOpen(openLatch.countDown())

    val shortResetTimeoutCb = new CircuitBreaker(system.scheduler, 1, 1000.millis.dilated, 50.millis.dilated)
      .onClose(closedLatch.countDown())
      .onHalfOpen(halfOpenLatch.countDown())
      .onOpen(openLatch.countDown())

    val longCallTimeoutCb = new CircuitBreaker(system.scheduler, 1, 5 seconds, 500.millis.dilated)
      .onClose(closedLatch.countDown())
      .onHalfOpen(halfOpenLatch.countDown())
      .onOpen(openLatch.countDown())

    val longResetTimeoutCb = new CircuitBreaker(system.scheduler, 1, 100.millis.dilated, 5 seconds)
      .onClose(closedLatch.countDown())
      .onHalfOpen(halfOpenLatch.countDown())
      .onOpen(openLatch.countDown())

    val multiFailureCb = new CircuitBreaker(system.scheduler, 5, 200.millis.dilated, 500.millis.dilated)
      .onClose(closedLatch.countDown())
      .onHalfOpen(halfOpenLatch.countDown())
      .onOpen(openLatch.countDown())
  }

  before {
    breakers = new TestCircuitBreakers
  }

  def checkLatch(latch: TestLatch) {
    Await.ready(latch, awaitTimeout)
  }

  def throwException = throw new TestException

  def sayHi = "hi"

  "A synchronous circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {

      intercept[TestException] {
        breakers.longResetTimeoutCb.withSyncCircuitBreaker(throwException)
      }
      checkLatch(breakers.openLatch)

      intercept[CircuitBreakerOpenException] {
        breakers.longResetTimeoutCb.withSyncCircuitBreaker(sayHi)
      }
    }

    "transition to half-open on reset timeout" in {
      intercept[TestException] {
        breakers.shortResetTimeoutCb.withSyncCircuitBreaker(throwException)
      }
      checkLatch(breakers.halfOpenLatch)
    }
  }

  "A synchronous circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      intercept[TestException] {
        breakers.shortResetTimeoutCb.withSyncCircuitBreaker(throwException)
      }
      checkLatch(breakers.halfOpenLatch)
      assert("hi" == breakers.shortResetTimeoutCb.withSyncCircuitBreaker(sayHi))
      checkLatch(breakers.closedLatch)
    }

    "open on exception in call" in {
      intercept[TestException] {
        breakers.shortResetTimeoutCb.withSyncCircuitBreaker(throwException)
      }
      checkLatch(breakers.halfOpenLatch)
      intercept[TestException] {
        breakers.shortResetTimeoutCb.withSyncCircuitBreaker(throwException)
      }
      checkLatch(breakers.openLatch)
    }
  }

  "A synchronous circuit breaker that is closed" must {
    "allow calls through" in {
      breakers.longCallTimeoutCb.withSyncCircuitBreaker(sayHi) must be("hi")
    }

    "increment failure count on failure" in {
      intercept[TestException] {
        breakers.longCallTimeoutCb.withSyncCircuitBreaker(throwException)
      }
      checkLatch(breakers.openLatch)
      breakers.longCallTimeoutCb.currentFailureCount must be(1)
    }

    "reset failure count after success" in {
      intercept[TestException] {
        breakers.multiFailureCb.withSyncCircuitBreaker(throwException)
      }

      breakers.multiFailureCb.currentFailureCount must be(1)
      breakers.multiFailureCb.withSyncCircuitBreaker(sayHi)
      breakers.multiFailureCb.currentFailureCount must be(0)
    }

    "increment failure count on callTimeout" in {
      breakers.shortCallTimeoutCb.withSyncCircuitBreaker({
        Thread.sleep(100.millis.dilated.toMillis)
      })
      breakers.shortCallTimeoutCb.currentFailureCount must be(1)
    }
  }

  "An asynchronous circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {
      breakers.longResetTimeoutCb.withCircuitBreaker(Future(throwException))

      checkLatch(breakers.openLatch)

      intercept[CircuitBreakerOpenException] {
        Await.result(
          breakers.longResetTimeoutCb.withCircuitBreaker(Future(sayHi)),
          awaitTimeout)
      }
    }

    "transition to half-open on reset timeout" in {
      breakers.shortResetTimeoutCb.withCircuitBreaker(Future(throwException))
      checkLatch(breakers.halfOpenLatch)
    }
  }

  "An asynchronous circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      breakers.shortResetTimeoutCb.withCircuitBreaker(Future(throwException))
      checkLatch(breakers.halfOpenLatch)

      Await.result(
        breakers.shortResetTimeoutCb.withCircuitBreaker(Future(sayHi)),
        awaitTimeout) must be("hi")
      checkLatch(breakers.closedLatch)
    }

    "re-open on exception in call" in {
      breakers.shortResetTimeoutCb.withCircuitBreaker(Future(throwException))
      checkLatch(breakers.halfOpenLatch)

      intercept[TestException] {
        Await.result(
          breakers.shortResetTimeoutCb.withCircuitBreaker(Future(throwException)),
          awaitTimeout)
      }
      checkLatch(breakers.openLatch)
    }

    "re-open on async failure" in {
      breakers.shortResetTimeoutCb.withCircuitBreaker(Future(throwException))
      checkLatch(breakers.halfOpenLatch)

      breakers.shortResetTimeoutCb.withCircuitBreaker(Future(throwException))
      checkLatch(breakers.openLatch)
    }
  }

  "An asynchronous circuit breaker that is closed" must {
    "allow calls through" in {
      Await.result(
        breakers.longCallTimeoutCb.withCircuitBreaker(Future(sayHi)),
        awaitTimeout) must be("hi")
    }

    "increment failure count on exception" in {
      intercept[TestException] {
        Await.result(
          breakers.longCallTimeoutCb.withCircuitBreaker(Future(throwException)),
          awaitTimeout)
      }
      checkLatch(breakers.openLatch)
      breakers.longCallTimeoutCb.currentFailureCount must be(1)
    }

    "increment failure count on async failure" in {
      breakers.longCallTimeoutCb.withCircuitBreaker(Future(throwException))
      checkLatch(breakers.openLatch)
      breakers.longCallTimeoutCb.currentFailureCount must be(1)
    }

    "reset failure count after success" in {
      breakers.multiFailureCb.withCircuitBreaker(Future(sayHi))
      val latch = TestLatch(4)
      for (n ← 1 to 4) breakers.multiFailureCb.withCircuitBreaker(Future(throwException))
      awaitCond(breakers.multiFailureCb.currentFailureCount == 4, awaitTimeout)
      breakers.multiFailureCb.withCircuitBreaker(Future(sayHi))
      awaitCond(breakers.multiFailureCb.currentFailureCount == 0, awaitTimeout)
    }

    "increment failure count on callTimeout" in {
      breakers.shortCallTimeoutCb.withCircuitBreaker {
        Future {
          Thread.sleep(100.millis.dilated.toMillis)
          sayHi
        }
      }

      checkLatch(breakers.openLatch)
      breakers.shortCallTimeoutCb.currentFailureCount must be(1)
    }
  }

}
