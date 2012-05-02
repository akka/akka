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
  var cb: CircuitBreaker = null

  before {
    halfOpenLatch = new CountDownLatch(1)
    openLatch = new CountDownLatch(1)
    closedLatch = new CountDownLatch(1)
    cb = new CircuitBreaker(system.scheduler, 1, 50 millis, 5 millis)
      .onClose(() ⇒ { closedLatch.countDown() })
      .onHalfOpen(() ⇒ { halfOpenLatch.countDown() })
      .onOpen(() ⇒ { openLatch.countDown() })
  }
  
  def checkLatch(latch: CountDownLatch) {
    var count = 0
    do {
      count += 1
    } while (count < 100 && !latch.await(10, TimeUnit.MILLISECONDS))
    if (count == 100)
      throw new RuntimeException("!! Hung waiting for latch to clear")
  }

  "A circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {
      intercept[Exception] {
        cb.withCircuitBreaker({ throw new Exception() })
      }
      checkLatch(openLatch)

      intercept[CircuitBreakerOpenException] {
        cb.withCircuitBreaker(() ⇒ { "hi" })
      }
    }

    "transition to half-open on reset timeout" in {
      intercept[Exception] {
        cb.withCircuitBreaker({ throw new Exception() })
      }
      checkLatch(halfOpenLatch)
    }
  }

  "A circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      intercept[Exception] {
        cb.withCircuitBreaker({ throw new Exception() })
      }
      checkLatch(halfOpenLatch)
      assert("hi" == cb.withCircuitBreaker({ "hi" }))
      checkLatch(closedLatch)
    }

    "open on exception in call" in {
      intercept[Exception] {
        cb.withCircuitBreaker({ throw new Exception() })
      }
      checkLatch(halfOpenLatch)
      intercept[Exception] {
        cb.withCircuitBreaker({ throw new Exception() })
      }
      checkLatch(openLatch)
    }
  }

  "A circuit breaker that is closed" must {
    "allow calls through" in {
      expect("hi") { cb.withCircuitBreaker({ "hi" }) }
    }

    "increment failure count on failure" in {
      intercept[Exception] {
        cb.withCircuitBreaker({ throw new Exception() })
      }
      checkLatch(closedLatch)
      assert(1 == cb.currentFailureCount())
    }

    "reset failure count after success" in {
      cb = new CircuitBreaker(system.scheduler, 2, 50 millis, 5 millis)
        .onClose(() ⇒ { closedLatch.countDown() })
        .onHalfOpen(() ⇒ { halfOpenLatch.countDown() })
        .onOpen(() ⇒ { openLatch.countDown() })

      intercept[Exception] {
        cb.withCircuitBreaker({ throw new Exception() })
      }
      checkLatch(closedLatch)

      assert(1 == cb.currentFailureCount())
      cb.withCircuitBreaker(() ⇒ { "hi" })
      assert(0 == cb.currentFailureCount())
    }

    "increment failure count on timeout" in {
      cb.withCircuitBreaker({ Thread.sleep(100L) })
      assert(1 == cb.currentFailureCount())
    }
  }

}