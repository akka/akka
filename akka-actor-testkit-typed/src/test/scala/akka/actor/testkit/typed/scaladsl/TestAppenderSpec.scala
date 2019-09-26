/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future

import akka.actor.testkit.typed.TestException
import org.scalatest.WordSpecLike
import org.slf4j.LoggerFactory

class TestAppenderSpec
    extends ScalaTestWithActorTestKit(
      """
  # increase to avoid spurious failures in "find unexpected async events withOccurrences(0)"
  akka.actor.testkit.typed.expect-no-message-default = 1000 ms
  """)
    with WordSpecLike
    with LogCapturing {

  class AnotherLoggerClass

  private val log = LoggerFactory.getLogger(getClass)

  "TestAppender and LoggingEventFilter" must {
    "filter errors without cause" in {
      LoggingEventFilter.error("an error").withOccurrences(2).intercept {
        log.error("an error")
        log.error("an error")
      }
    }

    "filter errors with cause" in {
      LoggingEventFilter.error("err").withCause[TestException].intercept {
        log.error("err", TestException("an error"))
      }
    }

    "filter warnings" in {
      LoggingEventFilter.warn("a warning").withOccurrences(2).intercept {
        log.error("an error")
        log.warn("a warning")
        log.error("an error")
        log.warn("a warning")
      }
    }

    "find excess messages" in {
      intercept[AssertionError] {
        LoggingEventFilter.warn("a warning").withOccurrences(2).intercept {
          log.error("an error")
          log.warn("a warning")
          log.warn("a warning")
          log.error("an error")
          // since this logging is synchronous it will notice 3 occurrences but expecting 2,
          // but note that it will not look for asynchronous excess messages when occurrences > 0 and it has
          // already found expected number
          log.warn("a warning") // 3rd
        }
      }.getMessage should include("Received 1 excess messages")
    }

    "only filter events for given logger name" in {
      val count = new AtomicInteger
      LoggingEventFilter
        .custom({
          case logEvent =>
            count.incrementAndGet()
            logEvent.message == "Hello from right logger" && logEvent.loggerName == classOf[AnotherLoggerClass].getName
        })
        .withOccurrences(2)
        .withLoggerName(classOf[AnotherLoggerClass].getName)
        .intercept {
          LoggerFactory.getLogger(classOf[AnotherLoggerClass]).info("Hello from right logger")
          log.info("Hello wrong logger")
          LoggerFactory.getLogger(classOf[AnotherLoggerClass]).info("Hello from right logger")
        }
      count.get should ===(2)
    }

    "find unexpected events withOccurrences(0)" in {
      LoggingEventFilter.warn("a warning").withOccurrences(0).intercept {
        log.error("an error")
        log.warn("another warning")
      }

      intercept[AssertionError] {
        LoggingEventFilter.warn("a warning").withOccurrences(0).intercept {
          log.error("an error")
          log.warn("a warning")
          log.warn("another warning")
        }
      }.getMessage should include("Received 1 excess messages")

      intercept[AssertionError] {
        LoggingEventFilter.warn("a warning").withOccurrences(0).intercept {
          log.warn("a warning")
          log.warn("a warning")
        }
      }.getMessage should include("Received 2 excess messages")

    }

    "find unexpected async events withOccurrences(0)" in {
      // expect-no-message-default = 1000 ms
      intercept[AssertionError] {
        LoggingEventFilter.warn("a warning").withOccurrences(0).intercept {
          Future {
            Thread.sleep(20)
            log.warn("a warning")
            log.warn("a warning")
          }(system.executionContext)
        }
      }
    }

  }

}
