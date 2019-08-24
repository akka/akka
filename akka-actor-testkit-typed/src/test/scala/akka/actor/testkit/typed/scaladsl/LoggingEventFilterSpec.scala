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

  "The LoggingEventFilter.error" must {
    "filter errors without cause" in {
      val filter = LoggingEventFilter.error()
      filter(errorNoCause) should ===(true)
    }

    "filter errors with cause" in {
      val filter = LoggingEventFilter.error()
      filter(errorWithCause(new AnError)) should ===(true)
    }

    "filter error with matching message" in {
      LoggingEventFilter.error(message = "this is an error").apply(errorWithCause(new AnError)) should ===(true)
      LoggingEventFilter.error(message = "this is an error").apply(errorNoCause) should ===(true)
      LoggingEventFilter.error(message = "this is another error").apply(errorNoCause) should ===(false)
    }
  }

  "The LoggingEventFilter[Exc]" must {
    "not filter errors without cause" in {
      val filter = LoggingEventFilter[AnError]()
      filter(errorNoCause) should ===(false)
    }

    "not filter errors with an unrelated cause" in {
      object AnotherError extends Exception
      val filter = LoggingEventFilter[AnError]()
      filter(errorWithCause(AnotherError)) should ===(false)
    }

    "filter errors with a matching cause" in {
      val filter = LoggingEventFilter[AnError]()
      filter(errorWithCause(new AnError)) should ===(true)
    }
    "filter errors with a matching cause and message" in {
      val filter = LoggingEventFilter[AnError](message = "this is an error")
      filter(errorWithCause(new AnError)) should ===(true)
    }
  }

  "The LoggingEventFilter.warning" must {
    "filter warnings without cause" in {
      val filter = LoggingEventFilter.warning()
      filter(warningNoCause) should ===(true)
    }
    "filter warning with cause" in {
      val filter = LoggingEventFilter.warning()
      filter(warningWithCause(new AnError)) should ===(true)
    }
    "filter warning with matching message" in {
      LoggingEventFilter.warning(message = "this is a warning").apply(warningWithCause(new AnError)) should ===(true)
      LoggingEventFilter.warning(message = "this is another warning").apply(warningWithCause(new AnError)) should ===(
        false)
    }

  }

}
