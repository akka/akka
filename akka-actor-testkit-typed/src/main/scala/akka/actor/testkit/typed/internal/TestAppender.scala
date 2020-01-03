/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import akka.actor.testkit.typed.LoggingEvent
import akka.annotation.InternalApi
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.AppenderBase

/**
 * INTERNAL API
 *
 * The `TestAppender` emits the logging events to the registered [[LoggingTestKitImpl]], which
 * are added and removed to the appender dynamically from tests.
 *
 * `TestAppender` is currently requiring Logback as SLF4J implementation.
 * Similar can probably be implemented with other backends, such as Log4j2.
 */
@InternalApi private[akka] object TestAppender {
  import LogbackUtil._

  private val TestAppenderName = "AkkaTestAppender"

  def setupTestAppender(loggerName: String): Unit = {
    val logbackLogger = getLogbackLogger(loggerName)
    logbackLogger.getAppender(TestAppenderName) match {
      case null =>
        val testAppender = new TestAppender
        testAppender.setName(TestAppenderName)
        testAppender.setContext(logbackLogger.getLoggerContext)
        testAppender.start()
        logbackLogger.addAppender(testAppender)
      case _: TestAppender =>
      // ok, already setup
      case other =>
        throw new IllegalStateException(s"Unexpected $TestAppenderName already added: $other")
    }
  }

  def addFilter(loggerName: String, filter: LoggingTestKitImpl): Unit =
    getTestAppender(loggerName).addTestFilter(filter)

  def removeFilter(loggerName: String, filter: LoggingTestKitImpl): Unit =
    getTestAppender(loggerName).removeTestFilter(filter)

  private def getTestAppender(loggerName: String): TestAppender = {
    val logger = getLogbackLogger(loggerName)
    logger.getAppender(TestAppenderName) match {
      case testAppender: TestAppender => testAppender
      case null =>
        throw new IllegalStateException(s"No $TestAppenderName was setup for logger [${logger.getName}]")
      case other =>
        throw new IllegalStateException(
          s"Unexpected $TestAppenderName already added for logger [${logger.getName}]: $other")
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class TestAppender extends AppenderBase[ILoggingEvent] {
  import LogbackUtil._

  private var filters: List[LoggingTestKitImpl] = Nil

  // invocations are synchronized via doAppend in AppenderBase
  override def append(event: ILoggingEvent): Unit = {
    import akka.util.ccompat.JavaConverters._

    val throwable = event.getThrowableProxy match {
      case p: ThrowableProxy =>
        Option(p.getThrowable)
      case _ => None
    }

    val loggingEvent = LoggingEvent(
      level = convertLevel(event.getLevel),
      message = event.getFormattedMessage,
      loggerName = event.getLoggerName,
      threadName = event.getThreadName,
      timeStamp = event.getTimeStamp,
      marker = Option(event.getMarker),
      throwable = throwable,
      mdc = event.getMDCPropertyMap.asScala.toMap)

    filter(loggingEvent)
  }

  private def filter(event: LoggingEvent): Boolean = {
    filters.exists(f =>
      try {
        f.apply(event)
      } catch {
        case _: Exception => false
      })
  }

  def addTestFilter(filter: LoggingTestKitImpl): Unit = synchronized {
    filters ::= filter
  }

  def removeTestFilter(filter: LoggingTestKitImpl): Unit = synchronized {
    @scala.annotation.tailrec
    def removeFirst(list: List[LoggingTestKitImpl], zipped: List[LoggingTestKitImpl] = Nil): List[LoggingTestKitImpl] =
      list match {
        case head :: tail if head == filter => tail.reverse_:::(zipped)
        case head :: tail                   => removeFirst(tail, head :: zipped)
        case Nil                            => filters // filter not found, just return original list
      }
    filters = removeFirst(filters)
  }

}
