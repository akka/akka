package akka.actor.mailbox

import akka.testkit.AkkaSpec
import akka.pattern.AskTimeoutException
import java.util.concurrent.{TimeoutException, TimeUnit}
import java.util.concurrent.locks.ReentrantLock
import org.scalatest.Assertions._

/**
 * akka.actor.mailbox
 * Date: 4/28/12
 * Time: 12:08 PM
 */

class CircuitBreakerFSMSpec extends AkkaSpec {
  val lock = new ReentrantLock()
  val isHalfOpen = lock.newCondition()
  val isOpen = lock.newCondition()
  val isClosed = lock.newCondition()

  def checkHalfOpen(cbFsm: CircuitBreakerFSM) {
    lock.lock()
    try
    {
      while(!cbFsm.isHalfOpen())
        isHalfOpen.await(5, TimeUnit.MILLISECONDS)
    }
    finally
    {
      lock.unlock()
    }

    assert(cbFsm.isHalfOpen(),"circuit breaker is not half-open")
  }

  def checkOpen(cbFsm: CircuitBreakerFSM) {
    lock.lock()
    try
    {
      while(!cbFsm.isOpen())
        isOpen.await(5, TimeUnit.MILLISECONDS)
    }
    finally
    {
      lock.unlock()
    }

    assert(cbFsm.isOpen(),"circuit breaker is not open")
  }

  def checkClosed(cbFsm: CircuitBreakerFSM) {
    lock.lock()
    try
    {
      while(!cbFsm.isClosed())
        isClosed.await(5, TimeUnit.MILLISECONDS)
    }
    finally
    {
      lock.unlock()
    }

    assert(cbFsm.isClosed(),"circuit breaker is not closed")
  }

  "A circuit breaker that is open" must {
    "throw exceptions when called before reset timeout" in {
      val cbFsm = new CircuitBreakerFSM(system,1,50,1,TimeUnit.MINUTES)
      intercept[Exception] {
        cbFsm.withCircuitBreaker({ throw new Exception() })
      }
      checkOpen(cbFsm)

      intercept[CircuitBreakerOpenException]
      {
        cbFsm.withCircuitBreaker(() => { "hi" })
      }
    }

    "transition to half-open on reset timeout" in {
      val cbFsm = new CircuitBreakerFSM(system,1,50,5,TimeUnit.MILLISECONDS)
      intercept[Exception] {
        cbFsm.withCircuitBreaker({ throw new Exception() })
      }
      checkHalfOpen(cbFsm)
    }
  }

  "A circuit breaker that is half-open" must {
    "pass through next call and close on success" in {
      val cbFsm = new CircuitBreakerFSM(system,1,50,5,TimeUnit.MILLISECONDS)
      intercept[Exception] {
        cbFsm.withCircuitBreaker({ throw new Exception() })
      }
      checkHalfOpen(cbFsm)
      assert("hi" == cbFsm.withCircuitBreaker({ "hi" }))
      checkClosed(cbFsm)
    }

    "open on exception in call" in {
      val cbFsm = new CircuitBreakerFSM(system,1,50,5,TimeUnit.MILLISECONDS)
      intercept[Exception] {
        cbFsm.withCircuitBreaker({ throw new Exception() })
      }
      checkHalfOpen(cbFsm)
      intercept[Exception] {
        cbFsm.withCircuitBreaker({ throw new Exception() })
      }
      checkOpen(cbFsm)
    }
  }

  "A circuit breaker that is closed" must {
    "allow calls through" in {
      val cbFsm = new CircuitBreakerFSM(system,1,1,1,TimeUnit.MILLISECONDS)
      expect("hi") { cbFsm.withCircuitBreaker({"hi"}) }
    }

    "increment failure count on failure" in {
      val cbFsm = new CircuitBreakerFSM(system,2,1,1,TimeUnit.DAYS)
      intercept[Exception] {
        cbFsm.withCircuitBreaker({ throw new Exception() })
      }
      checkClosed(cbFsm)
      assert(1 == cbFsm.currentFailureCount())
    }

    "reset failure count after success" in {
      val cbFsm = new CircuitBreakerFSM(system,2,1,1,TimeUnit.DAYS)
      intercept[Exception] {
        cbFsm.withCircuitBreaker({ throw new Exception() })
      }
      checkClosed(cbFsm)

      assert(1 == cbFsm.currentFailureCount())
      cbFsm.withCircuitBreaker(() => { "hi" })
      assert(0 == cbFsm.currentFailureCount())
    }

    "become open on timeout" in {
      val cbFsm = new CircuitBreakerFSM(system,1,5,1,TimeUnit.MILLISECONDS)
      intercept[TimeoutException] {
        cbFsm.withCircuitBreaker({ Thread.sleep(50L) })
      }
      checkOpen(cbFsm)
    }
  }

}