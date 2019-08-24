/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import akka.actor.testkit.typed.LoggingEvent
import akka.actor.testkit.typed.scaladsl.LoggingEventFilter
import akka.annotation.InternalApi
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * INTERNAL API
 *
 * The `TestAppender` emits the logging events to the registered [[LoggingEventFilter]], which
 * are added and removed to the appender dynamically from tests.
 *
 * `TestAppender` is currently requiring Logback as SLF4J implementation.
 * Similar can probably be implemented with other backends, such as Log4j2.
 */
@InternalApi private[akka] object TestAppender {
  private val TestAppenderName = "AkkaTestAppender"

  // FIXME #26537 detect if logback is not in classpath and fail more friendly,
  // also detect if other slf4j impl is used and fail friendly

  def setupTestAppender(loggerName: String): Unit = {
    val logbackLogger = getLogbackLogger(loggerName)
    logbackLogger.getAppender(TestAppenderName) match {
      case null =>
        logbackLogger.getLoggerContext
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

  def addFilter(loggerName: String, filter: LoggingEventFilter): Unit =
    getTestAppender(loggerName).addTestFilter(filter)

  def removeFilter(loggerName: String, filter: LoggingEventFilter): Unit =
    getTestAppender(loggerName).removeTestFilter(filter)

  private def loggerNameOrRoot(loggerName: String): String =
    if (loggerName == "") org.slf4j.Logger.ROOT_LOGGER_NAME else loggerName

  private def getLogbackLogger(loggerName: String): ch.qos.logback.classic.Logger = {
    LoggerFactory.getLogger(loggerNameOrRoot(loggerName)) match {
      case logger: ch.qos.logback.classic.Logger => logger
      case null =>
        throw new IllegalArgumentException(s"TestAppender couldn't find logger for [$loggerName].")
      case other =>
        throw new IllegalArgumentException(
          s"TestAppender requires Logback logger for [$loggerName], " +
          s"it was a [${other.getClass.getName}]")
    }
  }

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

  private var filters: List[LoggingEventFilter] = Nil

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

  private def convertLevel(level: ch.qos.logback.classic.Level): Level = {
    level.levelInt match {
      case ch.qos.logback.classic.Level.TRACE_INT => Level.TRACE
      case ch.qos.logback.classic.Level.DEBUG_INT => Level.DEBUG
      case ch.qos.logback.classic.Level.INFO_INT  => Level.INFO
      case ch.qos.logback.classic.Level.WARN_INT  => Level.WARN
      case ch.qos.logback.classic.Level.ERROR_INT => Level.ERROR
      case _ =>
        throw new IllegalArgumentException("Level " + level.levelStr + ", " + level.levelInt + " is unknown.")
    }
  }

  private def filter(event: LoggingEvent): Boolean = {
    filters.exists(f =>
      try {
        f.apply(event)
      } catch {
        case _: Exception => false
      })
  }

  def addTestFilter(filter: LoggingEventFilter): Unit = synchronized {
    filters ::= filter
  }

  def removeTestFilter(filter: LoggingEventFilter): Unit = synchronized {
    @scala.annotation.tailrec
    def removeFirst(list: List[LoggingEventFilter], zipped: List[LoggingEventFilter] = Nil): List[LoggingEventFilter] =
      list match {
        case head :: tail if head == filter => tail.reverse_:::(zipped)
        case head :: tail                   => removeFirst(tail, head :: zipped)
        case Nil                            => filters // filter not found, just return original list
      }
    filters = removeFirst(filters)
  }

}
