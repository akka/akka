/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.scalatest.matchers.MustMatchers
import akka.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class HarmlessSpec extends AkkaSpec with MustMatchers {

  "A Harmless extractor" must {

    "match ordinary RuntimeException" in {
      try {
        throw new RuntimeException("Boom")
      } catch {
        case Harmless(e) ⇒ // as expected
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
          case Harmless(e) ⇒ assert(false)
        }
      }
    }

    "not match InterruptedException" in {
      intercept[InterruptedException] {
        try {
          throw new InterruptedException("Simulated InterruptedException")
        } catch {
          case Harmless(e) ⇒ assert(false)
        }
      }
    }

    "be used together with InterruptedException" in {
      try {
        throw new InterruptedException("Simulated InterruptedException")
      } catch {
        case _: InterruptedException ⇒ // as expected
        case Harmless(e)             ⇒ assert(false)
      }

      try {
        throw new RuntimeException("Simulated RuntimeException")
      } catch {
        case Harmless(_) | _: InterruptedException ⇒ // as expected
      }
    }

  }

}