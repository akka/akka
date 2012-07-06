/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import akka.testkit._
import scala.concurrent.util.duration._
import scala.concurrent.{ Promise, Future, Await }

class CircuitBreakerMTSpec extends AkkaSpec {

  "A circuit breaker being called by many threads" must {
    val breaker = new CircuitBreaker(system.scheduler, 5, 100.millis.dilated, 500.millis.dilated)

    def openBreaker(): Unit =
      Await.ready(Future.sequence((1 to 5).map(_ ⇒ breaker.withCircuitBreaker(Future(throw new RuntimeException("FAIL"))).failed)), 1.second.dilated)

    "allow many calls while in closed state with no errors" in {

      val futures = for (i ← 1 to 100) yield breaker.withCircuitBreaker(Future { Thread.sleep(10); "succeed" })

      val result = Await.result(Future.sequence(futures), 5.second.dilated)

      result.size must be(100)
      result.toSet must be === Set("succeed")

    }

    "transition to open state upon reaching failure limit and fail-fast" in {
      openBreaker()

      val futures = for (i ← 1 to 100) yield breaker.withCircuitBreaker(Future {
        Thread.sleep(10); "success"
      }) recoverWith {
        case _: CircuitBreakerOpenException ⇒ Promise.successful("CBO").future
      }

      val result = Await.result(Future.sequence(futures), 5.second.dilated)

      result.size must be(100)
      result.toSet must be === Set("CBO")
    }

    "allow a single call through in half-open state" in {
      val halfOpenLatch = new TestLatch(1)
      breaker.onHalfOpen(halfOpenLatch.countDown())

      openBreaker()

      Await.ready(halfOpenLatch, 2.seconds.dilated)

      val futures = for (i ← 1 to 100) yield breaker.withCircuitBreaker(Future {
        Thread.sleep(10); "succeed"
      }) recoverWith {
        case _: CircuitBreakerOpenException ⇒ Promise.successful("CBO").future
      }

      val result = Await.result(Future.sequence(futures), 5.second.dilated)

      result.size must be(100)
      result.toSet must be === Set("succeed", "CBO")
    }

    "recover and reset the breaker after the reset timeout" in {
      val halfOpenLatch = new TestLatch(1)
      breaker.onHalfOpen(halfOpenLatch.countDown())
      openBreaker()
      Await.ready(halfOpenLatch, 5.seconds.dilated)
      Await.ready(breaker.withCircuitBreaker(Future("succeed")), 1.second.dilated)

      val futures = (1 to 100) map {
        i ⇒
          breaker.withCircuitBreaker(Future { Thread.sleep(10); "succeed" }) recoverWith {
            case _: CircuitBreakerOpenException ⇒ Promise.successful("CBO").future
          }
      }

      val result = Await.result(Future.sequence(futures), 5.second.dilated)

      result.size must be(100)
      result.toSet must be === Set("succeed")
    }
  }
}