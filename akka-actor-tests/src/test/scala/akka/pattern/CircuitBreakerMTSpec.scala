/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import akka.testkit._
import scala.concurrent.util.duration._
import org.scalatest.BeforeAndAfter
import scala.concurrent.{ Promise, Future, Await }

class CircuitBreakerMTSpec extends AkkaSpec with BeforeAndAfter {

  @volatile
  var breakers: BreakerState = null

  class BreakerState {

    val halfOpenLatch = new TestLatch(1)

    val breaker = new CircuitBreaker(system.scheduler, 5, 100.millis.dilated, 500.millis.dilated)
      .onHalfOpen(halfOpenLatch.countDown())

  }

  before {
    breakers = new BreakerState()
  }

  def unreliableCall(param: String) = {
    param match {
      case "fail" ⇒ throw new RuntimeException("FAIL")
      case _      ⇒ param
    }
  }

  def openBreaker: Unit = {
    for (i ← 1 to 5)
      Await.result(breakers.breaker.withCircuitBreaker(Future(unreliableCall("fail"))) recoverWith {
        case _ ⇒ Promise.successful("OK").future
      }, 1.second.dilated)
  }

  "A circuit breaker being called by many threads" must {
    "allow many calls while in closed state with no errors" in {

      val futures = for (i ← 1 to 100) yield breakers.breaker.withCircuitBreaker(Future { Thread.sleep(10); unreliableCall("succeed") })

      val futureList = Future.sequence(futures)

      val result = Await.result(futureList, 1.second.dilated)

      result.size must be(100)
      result.distinct.size must be(1)
      result.distinct must contain("succeed")

    }

    "transition to open state upon reaching failure limit and fail-fast" in {

      openBreaker

      val futures = for (i ← 1 to 100) yield breakers.breaker.withCircuitBreaker(Future {
        Thread.sleep(10); unreliableCall("success")
      }) recoverWith { case _: CircuitBreakerOpenException ⇒ Promise.successful("CBO").future }

      val futureList = Future.sequence(futures)

      val result = Await.result(futureList, 1.second.dilated)

      result.size must be(100)
      result.distinct.size must be(1)
      result.distinct must contain("CBO")
    }

    "allow a single call through in half-open state" in {
      openBreaker

      Await.ready(breakers.halfOpenLatch, 2.seconds.dilated)

      val futures = for (i ← 1 to 100) yield breakers.breaker.withCircuitBreaker(Future {
        Thread.sleep(10); unreliableCall("succeed")
      }) recoverWith { case _: CircuitBreakerOpenException ⇒ Promise.successful("CBO").future }

      val futureList = Future.sequence(futures)

      val result = Await.result(futureList, 1.second.dilated)

      result.size must be(100)
      result.distinct.size must be(2)
      result.distinct must contain("succeed")
      result.distinct must contain("CBO")
    }

    "recover and reset the breaker after the reset timeout" in {
      openBreaker

      Await.ready(breakers.halfOpenLatch, 2.seconds.dilated)

      Await.ready(breakers.breaker.withCircuitBreaker(Future(unreliableCall("succeed"))), 1.second.dilated)

      val futures = for (i ← 1 to 100) yield breakers.breaker.withCircuitBreaker(Future {
        Thread.sleep(10); unreliableCall("succeed")
      }) recoverWith {
        case _: CircuitBreakerOpenException ⇒ Promise.successful("CBO").future
      }

      val futureList = Future.sequence(futures)

      val result = Await.result(futureList, 1.second.dilated)

      result.size must be(100)
      result.distinct.size must be(1)
      result.distinct must contain("succeed")
    }
  }

}