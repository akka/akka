/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.actor.testkit.typed.LoggingEvent
import org.scalatest.WordSpecLike
import org.slf4j.event.Level

class LoggingEventFilterSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  private class AnError extends Exception
  private def errorNoCause =
    LoggingEvent(
      level = Level.ERROR,
      loggerName = getClass.getName,
      message = "this is an error",
      threadName = Thread.currentThread().getName,
      timeStamp = System.currentTimeMillis())
  private def errorWithCause(cause: Throwable) =
    LoggingEvent(
      level = Level.ERROR,
      loggerName = getClass.getName,
      message = "this is an error",
      threadName = Thread.currentThread().getName,
      timeStamp = System.currentTimeMillis(),
      marker = None,
      throwable = Option(cause),
      mdc = Map.empty)
  private def warningNoCause =
    LoggingEvent(
      level = Level.WARN,
      loggerName = getClass.getName,
      message = "this is a warning",
      threadName = Thread.currentThread().getName,
      timeStamp = System.currentTimeMillis())
  private def warningWithCause(cause: Throwable) =
    LoggingEvent(
      level = Level.WARN,
      loggerName = getClass.getName,
      message = "this is a warning",
      threadName = Thread.currentThread().getName,
      timeStamp = System.currentTimeMillis(),
      marker = None,
      throwable = Option(cause),
      mdc = Map.empty)
  private def warningWithSource(source: String) =
    LoggingEvent(
      level = Level.WARN,
      loggerName = getClass.getName,
      message = "this is a warning",
      threadName = Thread.currentThread().getName,
      timeStamp = System.currentTimeMillis(),
      marker = None,
      throwable = None,
      mdc = Map("akkaSource" -> source))

  "The LoggingEventFilter.error" must {
    "filter errors without cause" in {
      val filter = LoggingEventFilter.empty.withLogLevel(Level.ERROR)
      filter.matches(errorNoCause) should ===(true)
    }

    "filter errors with cause" in {
      val filter = LoggingEventFilter.empty.withLogLevel(Level.ERROR)
      filter.matches(errorWithCause(new AnError)) should ===(true)
    }

    "filter error with matching message" in {
      LoggingEventFilter.error("an error").matches(errorWithCause(new AnError)) should ===(true)
      LoggingEventFilter.error("an error").matches(errorNoCause) should ===(true)
      LoggingEventFilter.error("another error").matches(errorNoCause) should ===(false)
    }

    "filter with matching MDC" in {
      LoggingEventFilter.empty.withMdc(Map("a" -> "A")).matches(errorNoCause.copy(mdc = Map("a" -> "A"))) should ===(
        true)
      LoggingEventFilter.empty
        .withMdc(Map("a" -> "A", "b" -> "B"))
        .matches(errorNoCause.copy(mdc = Map("a" -> "A", "b" -> "B"))) should ===(true)
      LoggingEventFilter.empty
        .withMdc(Map("a" -> "A"))
        .matches(errorNoCause.copy(mdc = Map("a" -> "A", "b" -> "B"))) should ===(true)
      LoggingEventFilter.empty
        .withMdc(Map("a" -> "A", "b" -> "B"))
        .matches(errorNoCause.copy(mdc = Map("a" -> "A"))) should ===(false)
      LoggingEventFilter.empty.withMdc(Map("a" -> "A", "b" -> "B")).matches(errorNoCause) should ===(false)
    }
  }

  "The LoggingEventFilter with cause" must {
    "not filter errors without cause" in {
      val filter = LoggingEventFilter.error[AnError]
      filter.matches(errorNoCause) should ===(false)
    }

    "not filter errors with an unrelated cause" in {
      object AnotherError extends Exception
      val filter = LoggingEventFilter.error[AnError]
      filter.matches(errorWithCause(AnotherError)) should ===(false)
    }

    "filter errors with a matching cause" in {
      val filter = LoggingEventFilter.error[AnError]
      filter.matches(errorWithCause(new AnError)) should ===(true)
    }
    "filter errors with a matching cause and message" in {
      val filter = LoggingEventFilter.error("this is an error").withCause[AnError]
      filter.matches(errorWithCause(new AnError)) should ===(true)
    }
  }

  "The LoggingEventFilter.warn" must {
    "filter warnings without cause" in {
      val filter = LoggingEventFilter.empty.withLogLevel(Level.WARN)
      filter.matches(warningNoCause) should ===(true)
    }
    "filter warning with cause" in {
      val filter = LoggingEventFilter.empty.withLogLevel(Level.WARN)
      filter.matches(warningWithCause(new AnError)) should ===(true)
    }
    "filter warning with matching message" in {
      LoggingEventFilter.warn("this is a warning").matches(warningWithCause(new AnError)) should ===(true)
      LoggingEventFilter.warn("this is another warning").matches(warningWithCause(new AnError)) should ===(false)
    }
    "filter warning with matching source" in {
      val source = "akka://Sys/user/foo"
      LoggingEventFilter.empty
        .withLogLevel(Level.WARN)
        .withSource(source)
        .matches(warningWithSource(source)) should ===(true)
      LoggingEventFilter.empty
        .withLogLevel(Level.WARN)
        .withSource("akka://Sys/user/bar")
        .matches(warningWithSource(source)) should ===(false)
    }

  }

}
