/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.scalatest.matchers.MustMatchers
import akka.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class NonFatalSpec extends AkkaSpec with MustMatchers {

  "A NonFatal extractor" must {

    "match ordinary RuntimeException" in {
      try {
        throw new RuntimeException("Boom")
      } catch {
        case NonFatal(e) ⇒ // as expected
      }
    }

    "not match StackOverflowError" in {
      //not @tailrec
      def blowUp(n: Long): Long = {
        blowUp(n + 1) + 1
      }

      intercept[StackOverflowError] {
        try {
          blowUp(0)
        } catch {
          case NonFatal(e) ⇒ assert(false)
        }
      }
    }

    "not match InterruptedException" in {
      intercept[InterruptedException] {
        try {
          throw new InterruptedException("Simulated InterruptedException")
        } catch {
          case NonFatal(e) ⇒ assert(false)
        }
      }
    }

  }

  "A NonFatalOrInterrupted extractor" must {

    "match InterruptedException" in {
      try {
        throw new InterruptedException("Simulated InterruptedException")
      } catch {
        case NonFatalOrInterrupted(e) ⇒ // as expected
      }
    }

  }
}