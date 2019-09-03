/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.TestException
import org.scalatest.WordSpecLike
import org.slf4j.LoggerFactory

class TestAppenderSpec extends ScalaTestWithActorTestKit with WordSpecLike {

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

    "only filter events for logger given in interceptLogger" in {
      val count = new AtomicInteger
      LoggingEventFilter
        .custom({
          case logEvent =>
            count.incrementAndGet()
            logEvent.message == "Hello from right logger" && logEvent.loggerName == classOf[AnotherLoggerClass].getName
        })
        .withOccurrences(2)
        .interceptLogger(classOf[AnotherLoggerClass].getName) {
          LoggerFactory.getLogger(classOf[AnotherLoggerClass]).info("Hello from right logger")
          log.info("Hello wrong logger")
          LoggerFactory.getLogger(classOf[AnotherLoggerClass]).info("Hello from right logger")
        }
      count.get should ===(2)
    }

  }

}
