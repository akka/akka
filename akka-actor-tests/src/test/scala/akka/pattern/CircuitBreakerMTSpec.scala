/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import scala.collection.immutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future

import akka.testkit._

class CircuitBreakerMTSpec extends AkkaSpec {
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  "A circuit breaker being called by many threads" must {
    val callTimeout = 2.seconds.dilated
    val resetTimeout = 3.seconds.dilated
    val maxFailures = 5
    def newBreaker = new CircuitBreaker(system.scheduler, maxFailures, callTimeout, resetTimeout)

    def openBreaker(breaker: CircuitBreaker): Unit = {
      // returns true if the breaker is open
      def failingCall(): Boolean =
        Await.result(breaker.withCircuitBreaker(Future.failed(new RuntimeException("FAIL"))).recover {
          case _: CircuitBreakerOpenException => true
          case _                              => false
        }, remainingOrDefault)

      // fire some failing calls
      (1 to (maxFailures + 1)).foreach { _ =>
        failingCall()
      }
      // and then continue with failing calls until the breaker is open
      awaitCond(failingCall())
    }

    def testCallsWithBreaker(breaker: CircuitBreaker, numberOfCalls: Int): immutable.IndexedSeq[Future[String]] = {
      for (_ <- 1 to numberOfCalls)
        yield makeCallWithBreaker(breaker)
    }

    def makeCallWithBreaker(breaker: CircuitBreaker): Future[String] =
      breaker.withCircuitBreaker(Future.successful("succeed")).recoverWith {
        case _: CircuitBreakerOpenException =>
          Future.successful("CBO")
      }

    "allow many calls while in closed state with no errors" in {
      val futures = testCallsWithBreaker(newBreaker, 10)
      val result = Await.result(Future.sequence(futures), 5.second.dilated)
      result.size should ===(10)
      result.toSet should ===(Set("succeed"))
    }

    "transition to open state upon reaching failure limit and fail-fast" in {
      val breaker = newBreaker
      openBreaker(breaker)

      breaker.isOpen shouldBe true
      val call = makeCallWithBreaker(breaker)
      val result = Await.result(call, 5.second.dilated)
      result shouldBe "CBO"
    }

    "allow a single call through in half-open state" in {
      val breaker = newBreaker
      val halfOpenLatch = new TestLatch(1)
      breaker.onHalfOpen(halfOpenLatch.countDown())
      openBreaker(breaker)

      // breaker should become half-open after a while
      Await.ready(halfOpenLatch, resetTimeout + 1.seconds.dilated)

      breaker.isHalfOpen shouldBe true

      val latch = new TestLatch(1)
      val firstCall =
        breaker.withCircuitBreaker(Future {
          // this call closes the CB,
          // but only after next call fails and touches the latch
          Await.ready(latch, 5.seconds)
          "succeed"
        })

      val secondCall =
        breaker.withCircuitBreaker(Future.successful("this should have failed")).recoverWith {
          case _: CircuitBreakerOpenException =>
            latch.countDown()
            Future.successful("CBO")
        }

      val firstResult = Await.result(firstCall, 5.second.dilated)
      firstResult shouldBe "succeed"

      val secondResult = Await.result(secondCall, 5.second.dilated)
      secondResult shouldBe "CBO"

      breaker.isClosed shouldBe true

    }

    "recover and reset the breaker after the reset timeout" in {
      val breaker = newBreaker

      val halfOpenLatch = new TestLatch(1)
      breaker.onHalfOpen(halfOpenLatch.countDown())
      openBreaker(breaker)

      // breaker should become half-open after a while
      Await.ready(halfOpenLatch, resetTimeout + 1.seconds.dilated)

      // one successful call should close the latch
      val closedLatch = new TestLatch(1)
      breaker.onClose(closedLatch.countDown())
      breaker.withCircuitBreaker(Future.successful("succeed"))
      Await.ready(closedLatch, 5.seconds.dilated)

      breaker.isClosed shouldBe true

      val call = makeCallWithBreaker(breaker)
      val result = Await.result(call, 5.second.dilated)
      result shouldBe "succeed"
    }
  }
}
