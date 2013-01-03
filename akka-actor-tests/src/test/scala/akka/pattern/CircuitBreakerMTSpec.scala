/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import akka.testkit._
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }
import scala.annotation.tailrec

class CircuitBreakerMTSpec extends AkkaSpec {
  implicit val ec = system.dispatcher
  "A circuit breaker being called by many threads" must {
    val callTimeout = 1.second.dilated
    val resetTimeout = 2.seconds.dilated
    val breaker = new CircuitBreaker(system.scheduler, 5, callTimeout, resetTimeout)
    val numberOfTestCalls = 100

    def openBreaker(): Unit = {
      @tailrec def call(attemptsLeft: Int): Unit = {
        attemptsLeft must be > (0)
        if (Await.result(breaker.withCircuitBreaker(Future(throw new RuntimeException("FAIL"))) recover {
          case _: CircuitBreakerOpenException ⇒ false
          case _                              ⇒ true
        }, remaining)) call(attemptsLeft - 1)
      }
      call(10)
    }

    def testCallsWithBreaker(): immutable.IndexedSeq[Future[String]] = {
      val aFewActive = new TestLatch(5)
      for (_ ← 1 to numberOfTestCalls) yield breaker.withCircuitBreaker(Future {
        aFewActive.countDown()
        Await.ready(aFewActive, 5.seconds.dilated)
        "succeed"
      }) recoverWith {
        case _: CircuitBreakerOpenException ⇒
          aFewActive.countDown()
          Future.successful("CBO")
      }
    }

    "allow many calls while in closed state with no errors" in {
      val futures = testCallsWithBreaker()
      val result = Await.result(Future.sequence(futures), 5.second.dilated)
      result.size must be(numberOfTestCalls)
      result.toSet must be === Set("succeed")
    }

    "transition to open state upon reaching failure limit and fail-fast" in {
      openBreaker()
      val futures = testCallsWithBreaker()
      val result = Await.result(Future.sequence(futures), 5.second.dilated)
      result.size must be(numberOfTestCalls)
      result.toSet must be === Set("CBO")
    }

    "allow a single call through in half-open state" in {
      val halfOpenLatch = new TestLatch(1)
      breaker.onHalfOpen(halfOpenLatch.countDown())

      openBreaker()

      // breaker should become half-open after a while
      Await.ready(halfOpenLatch, resetTimeout + 1.seconds.dilated)

      val futures = testCallsWithBreaker()
      val result = Await.result(Future.sequence(futures), 5.second.dilated)
      result.size must be(numberOfTestCalls)
      result.toSet must be === Set("succeed", "CBO")
    }

    "recover and reset the breaker after the reset timeout" in {
      val halfOpenLatch = new TestLatch(1)
      breaker.onHalfOpen(halfOpenLatch.countDown())
      openBreaker()

      // breaker should become half-open after a while
      Await.ready(halfOpenLatch, resetTimeout + 1.seconds.dilated)

      // one successful call should close the latch
      val closedLatch = new TestLatch(1)
      breaker.onClose(closedLatch.countDown())
      breaker.withCircuitBreaker(Future("succeed"))
      Await.ready(closedLatch, 5.seconds.dilated)

      val futures = testCallsWithBreaker()
      val result = Await.result(Future.sequence(futures), 5.second.dilated)
      result.size must be(numberOfTestCalls)
      result.toSet must be === Set("succeed")
    }
  }
}