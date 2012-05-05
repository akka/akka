package akka.actor.mailbox

import akka.testkit.AkkaSpec
import akka.util.duration._
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import org.scalatest.BeforeAndAfter

/**
 * akka.actor.mailbox
 * Date: 4/28/12
 * Time: 12:08 PM
 */

class CircuitBreakerSpec extends AkkaSpec with BeforeAndAfter {
  var halfOpenLatch: CountDownLatch = null
  var openLatch: CountDownLatch = null
  var closedLatch: CountDownLatch = null

  val shortCallTimeoutCb = new CircuitBreaker(system.scheduler, 1, 5 millis, 50 millis)
        .onClose(() ⇒ { closedLatch.countDown() })
        .onHalfOpen(() ⇒ { halfOpenLatch.countDown() })
        .onOpen(() ⇒ { openLatch.countDown() })

  val shortResetTimeoutCb = new CircuitBreaker(system.scheduler, 1, 100 millis, 5 millis)
        .onClose(() ⇒ { closedLatch.countDown() })
        .onHalfOpen(() ⇒ { halfOpenLatch.countDown() })
        .onOpen(() ⇒ { openLatch.countDown() })

  val longCallTimeoutCb = new CircuitBreaker(system.scheduler, 1, 5 seconds, 50 millis)
        .onClose(() ⇒ { closedLatch.countDown() })
        .onHalfOpen(() ⇒ { halfOpenLatch.countDown() })
        .onOpen(() ⇒ { openLatch.countDown() })

  val longResetTimeoutCb = new CircuitBreaker(system.scheduler, 1, 100 millis, 5 seconds)
        .onClose(() ⇒ { closedLatch.countDown() })
        .onHalfOpen(() ⇒ { halfOpenLatch.countDown() })
        .onOpen(() ⇒ { openLatch.countDown() })

  val multiFailureCb = new CircuitBreaker(system.scheduler, 5, 10 millis, 50 millis)
        .onClose(() ⇒ { closedLatch.countDown() })
        .onHalfOpen(() ⇒ { halfOpenLatch.countDown() })
        .onOpen(() ⇒ { openLatch.countDown() })

  before {
    halfOpenLatch = new CountDownLatch(1)
    openLatch = new CountDownLatch(1)
    closedLatch = new CountDownLatch(1)
  }

  def checkLatch(latch: CountDownLatch) {
    var count = 0
    do {
      count += 1
    } while (count < 100 && !latch.await(5, TimeUnit.MILLISECONDS))
    if (count == 100)
      throw new RuntimeException("!! Hung waiting for latch to clear")
  }

  def throwException() = throw new Exception()
  def sayHi = "hi"

  "A circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {
      intercept[Exception] {
        longResetTimeoutCb.withCircuitBreaker(throwException())
      }
      checkLatch(openLatch)

      intercept[CircuitBreakerOpenException] {
        longResetTimeoutCb.withCircuitBreaker(sayHi)
      }
    }

    "transition to half-open on reset timeout" in {
      intercept[Exception] {
        shortResetTimeoutCb.withCircuitBreaker(throwException())
      }
      checkLatch(halfOpenLatch)
    }
  }

  "A circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      intercept[Exception] {
        shortResetTimeoutCb.withCircuitBreaker(throwException())
      }
      checkLatch(halfOpenLatch)
      assert("hi" == shortResetTimeoutCb.withCircuitBreaker(sayHi))
      checkLatch(closedLatch)
    }

    "open on exception in call" in {
      intercept[Exception] {
        shortResetTimeoutCb.withCircuitBreaker(throwException())
      }
      checkLatch(halfOpenLatch)
      intercept[Exception] {
        shortResetTimeoutCb.withCircuitBreaker(throwException())
      }
      checkLatch(openLatch)
    }
  }

  "A circuit breaker that is closed" must {
    "allow calls through" in {
      expect("hi") { longCallTimeoutCb.withCircuitBreaker(sayHi) }
    }

    "increment failure count on failure" in {
      intercept[Exception] {
        longCallTimeoutCb.withCircuitBreaker(throwException())
      }
      checkLatch(openLatch)
      assert(1 == longCallTimeoutCb.currentFailureCount())
    }

    "reset failure count after success" in {
      intercept[Exception] {
        multiFailureCb.withCircuitBreaker(throwException())
      }

      assert(1 == multiFailureCb.currentFailureCount())
      multiFailureCb.withCircuitBreaker(sayHi)
      assert(0 == multiFailureCb.currentFailureCount())
    }

    "increment failure count on timeout" in {
      shortCallTimeoutCb.withCircuitBreaker({ Thread.sleep(50L) })
      assert(1 == shortCallTimeoutCb.currentFailureCount())
    }
  }

  "An asynchronous circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {
      val handle = longResetTimeoutCb.createAsyncHandle()
      handle.onAsyncFailure()

      checkLatch(openLatch)

      val handle2 = longResetTimeoutCb.createAsyncHandle()
      intercept[CircuitBreakerOpenException] {
        handle2.withCircuitBreaker(sayHi)
      }
    }

    "transition to half-open on reset timeout" in {
      val handle = shortResetTimeoutCb.createAsyncHandle()
      handle.onAsyncFailure()
      checkLatch(halfOpenLatch)
    }
  }

  "An asynchronous circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      val handle = shortResetTimeoutCb.createAsyncHandle()
      handle.onAsyncFailure()
      checkLatch(halfOpenLatch)

      val handle2 = shortResetTimeoutCb.createAsyncHandle()
      assert("hi" == handle2.withCircuitBreaker(sayHi))
      handle2.onAsyncSuccess()
      checkLatch(closedLatch)
    }

    "re-open on exception in call" in {
      val handle = shortResetTimeoutCb.createAsyncHandle()
      handle.onAsyncFailure()
      checkLatch(halfOpenLatch)

      val handle2 = shortResetTimeoutCb.createAsyncHandle()
      intercept[Exception] {
        handle2.withCircuitBreaker(throwException())
      }
      checkLatch(openLatch)
    }

    "re-open on async failure" in {
      val handle = shortResetTimeoutCb.createAsyncHandle()
      handle.onAsyncFailure()
      checkLatch(halfOpenLatch)

      val handle2 = shortResetTimeoutCb.createAsyncHandle()
      handle2.onAsyncFailure()
      checkLatch(openLatch)
    }
  }

  "An asynchronous circuit breaker that is closed" must {
    "allow calls through" in {
      val handle = longCallTimeoutCb.createAsyncHandle()
      expect("hi") { handle.withCircuitBreaker(sayHi) }
    }

    "increment failure count on exception" in {
      val handle = longCallTimeoutCb.createAsyncHandle()
      intercept[Exception] {
        handle.withCircuitBreaker(throwException())
      }
      checkLatch(openLatch)
      assert(1 == longCallTimeoutCb.currentFailureCount(), longCallTimeoutCb.currentFailureCount()+" should be 1")
    }

    "increment failure count on async failure" in {
      val handle = longCallTimeoutCb.createAsyncHandle()
      handle.onAsyncFailure()
      checkLatch(openLatch)
      assert(1 == longCallTimeoutCb.currentFailureCount(), longCallTimeoutCb.currentFailureCount()+" should be 1")
    }

    "reset failure count after success" in {
      val handle = multiFailureCb.createAsyncHandle()

      handle.withCircuitBreaker(sayHi)
      handle.onAsyncFailure()
      assert(1 == multiFailureCb.currentFailureCount(), multiFailureCb.currentFailureCount()+" should be 1")

      val handle2 = multiFailureCb.createAsyncHandle()
      handle2.withCircuitBreaker(sayHi)
      handle2.onAsyncSuccess()
      assert(0 == multiFailureCb.currentFailureCount(), multiFailureCb.currentFailureCount()+" should be 0")
    }

    "increment failure count on timeout" in {
      val handle = shortCallTimeoutCb.createAsyncHandle()
      handle.withCircuitBreaker(sayHi)
      Thread.sleep(150L)
      handle.onAsyncSuccess()
      checkLatch(openLatch)
      assert(1 == shortCallTimeoutCb.currentFailureCount())
    }
  }
}