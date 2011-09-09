/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import akka.util.duration._

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import java.util.concurrent.{ TimeUnit, CountDownLatch }

class CircuitBreakerSpec extends WordSpec with MustMatchers {

  class TestClass(cb: CircuitBreaker) {

    def doNothing = {
      cb {
        // do nothing
      }
    }

    def throwException: Long = {
      cb {
        throw new java.lang.IllegalArgumentException
      }
    }
  }

  val openLatch = new CountDownLatch(1)
  val closeLatch = new CountDownLatch(1)
  val halfOpenLatch = new CountDownLatch(1)

  val cb = CircuitBreaker(CircuitBreaker.Config(100.millis, 10))
  cb onOpen {
    openLatch.countDown()
  } onClose {
    closeLatch.countDown()
  } onHalfOpen {
    halfOpenLatch.countDown()
  }

  "A CircuitBreaker" must {

    "remain closed (no exceptions are thrown)" in {
      for (i ← 1 to 20) {
        new TestClass(cb).doNothing
      }
    }

    "be changing states correctly" in {

      /**
       * 10 failures throwing IllegalArgumentException
       */
      for (i ← 1 to 10) {
        evaluating { (new TestClass(cb).throwException) } must produce[java.lang.IllegalArgumentException]
      }

      /**
       * Should be OPEN.
       */
      for (i ← 1 to 10) {
        intercept[IllegalArgumentException](new TestClass(cb).throwException)
        assert(openLatch.await(30, TimeUnit.SECONDS) === true)
      }

      /**
       * Sleep for more than 100ms
       */
      Thread.sleep(200)

      /**
       * Should be HALF OPEN after 100 millis timeout.
       */
      intercept[IllegalArgumentException](new TestClass(cb).throwException)
      assert(halfOpenLatch.await(30, TimeUnit.SECONDS) === true)

      /**
       * Should be OPEN again.
       */
      for (i ← 1 to 10) {
        intercept[IllegalArgumentException](new TestClass(cb).throwException)
        assert(openLatch.await(30, TimeUnit.SECONDS) === true)
      }
    }
  }
}
