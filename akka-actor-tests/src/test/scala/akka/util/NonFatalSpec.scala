/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.scalatest.matchers.MustMatchers
import akka.testkit.AkkaSpec
import java.io.IOException

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

    "match StackOverflowError" in {
      //not @tailrec
      def blowUp(n: Long): Long = {
        blowUp(n + 1) + 1
      }

      try {
        blowUp(0)
      } catch {
        case NonFatal(e) ⇒ // as expected
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

    "be used together with InterruptedException" in {
      try {
        throw new InterruptedException("Simulated InterruptedException")
      } catch {
        case _: InterruptedException ⇒ // as expected
        case NonFatal(e)             ⇒ assert(false)
      }

      try {
        throw new RuntimeException("Simulated RuntimeException")
      } catch {
        case NonFatal(_) | _: InterruptedException ⇒ // as expected
      }
    }

    "be able to rethrow non-RuntimeException" in {
      val ioe = new IOException
      val e = new Exception
      val re = new RuntimeException
      intercept[IOException] { akka.jsr166y.ForkJoinPool.rethrow(ioe) } must be(ioe)
      intercept[Exception] { akka.jsr166y.ForkJoinPool.rethrow(e) } must be(e)
      intercept[RuntimeException] { akka.jsr166y.ForkJoinPool.rethrow(re) } must be(re)
    }

  }

}