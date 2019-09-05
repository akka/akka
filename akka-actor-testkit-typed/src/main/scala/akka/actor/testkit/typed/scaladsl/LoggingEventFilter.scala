/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import scala.reflect.ClassTag

import akka.actor.testkit.typed.LoggingEvent
import akka.actor.testkit.typed.internal.LoggingEventFilterImpl
import akka.actor.typed.ActorSystem
import akka.annotation.DoNotInherit
import org.slf4j.event.Level

/**
 * Facilities for selectively filtering out expected events from logging
 * to verify that they were logged.
 *
 * Requires Logback.
 *
 * See the companion object for convenient factory methods.
 *
 * Not for user extension.
 */
@DoNotInherit trait LoggingEventFilter {

  /**
   * Number of events the filter is supposed to match. By default 1.
   */
  def withOccurrences(newOccurrences: Int): LoggingEventFilter

  /**
   * Matching events with the given log level.
   */
  def withLogLevel(newLogLevel: Level): LoggingEventFilter

  /**
   * Matching events with the given logger name or sub-names in the same way
   * as configuration loggers are configured in logback.xml.
   * By default the root logger is used.
   */
  def withLoggerName(newLoggerName: String): LoggingEventFilter

  /**
   * Matching events that have "akkaSource" MDC value equal to the given value.
   * "akkaSource" is typically the actor path.
   */
  def withSource(newSource: String): LoggingEventFilter

  /**
   * Matching events with a message that contains the given value.
   */
  def withMessageContains(newMessageContains: String): LoggingEventFilter

  /**
   * Matching events with a message that matches the given regular expression.
   */
  def withMessageRegex(newMessageRegex: String): LoggingEventFilter

  /**
   * Matching events with an included `throwable` that is a class or subclass of the given
   * `Throwable` `ClassTag`.
   */
  def withCause[A <: Throwable: ClassTag]: LoggingEventFilter

  /**
   * Matching events with MDC containing all entries of the given `Map`.
   * The event MDC may have more entries than the given `Map`.
   */
  def withMdc(newMdc: Map[String, String]): LoggingEventFilter

  /**
   * Matching events for which the supplied function returns`true`.
   */
  def withCustom(newCustom: Function[LoggingEvent, Boolean]): LoggingEventFilter

  /**
   * @return `true` if the event matches the conditions of the filter.
   */
  def matches(event: LoggingEvent): Boolean

  /**
   * Apply this filter while executing the given code block.
   * Assert that this filter has matched as often as requested by its
   * `occurrences` parameter specifies.
   *
   * Care is taken to remove the filter when the block is finished or aborted.
   */
  def intercept[T](code: => T)(implicit system: ActorSystem[_]): T

}

/**
 * Facilities for selectively matching expected events from logging.
 *
 * Requires Logback.
 */
object LoggingEventFilter {

  /**
   * An empty filter that doesn't match any events.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def empty: LoggingEventFilter = LoggingEventFilterImpl.empty

  /**
   * Create a filter for events with a log message
   * that contains the given `messageIncludes`.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def messageContains(str: String): LoggingEventFilter =
    empty.withMessageContains(str)

  /**
   * Create a filter for TRACE level events with a log message
   * that contains the given `messageIncludes`.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def trace(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.TRACE)

  /**
   * Create a filter for DEBUG level events with a log message
   * that contains the given `messageIncludes`.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def debug(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.DEBUG)

  /**
   * Create a filter for INFO level events with a log message
   * that contains the given `messageIncludes`.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def info(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.INFO)

  /**
   * Create a filter for WARN level events with a log message
   * that contains the given `messageIncludes`.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def warn(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.WARN)

  /**
   * Create a filter for WARN level events with a an included
   * `throwable` that is a class or subclass of the given
   * `Throwable` `ClassTag`.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def warn[A <: Throwable: ClassTag]: LoggingEventFilter =
    empty.withLogLevel(Level.WARN).withCause[A]

  /**
   * Create a filter for ERROR level events with a log message
   * that contains the given `messageIncludes`.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def error(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.ERROR)

  /**
   * Create a filter for WARN level events with a an included
   * `throwable` that is a class or subclass of the given
   * `Throwable` `ClassTag`.
   *
   * More conditions can be added to the returned [LoggingEventFilter].
   */
  def error[A <: Throwable: ClassTag]: LoggingEventFilter =
    empty.withLogLevel(Level.ERROR).withCause[A]

  /**
   * Create a custom event filter. The filter will match those events for
   * which the supplied function returns `true`.
   */
  def custom(test: Function[LoggingEvent, Boolean]): LoggingEventFilter =
    empty.withCustom(test)

  /**
   * Filter for the logging of dead letters.
   */
  def deadLetters(): LoggingEventFilter =
    empty.withLogLevel(Level.INFO).withMessageRegex(".*was not delivered.*dead letters encountered.*")
}
