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
 * Facilities for selectively filtering out expected events from logging so
 * that you can keep your test run’s console output clean and do not miss real
 * error messages.
 *
 * See the companion object for convenient factory methods.
 *
 * If the `occurrences` is set to Int.MaxValue, no tracking is done.
 *
 * Not for user extension.
 */
@DoNotInherit trait LoggingEventFilter {

  def withOccurrences(newOccurrences: Int): LoggingEventFilter

  def withLogLevel(newLogLevel: Level): LoggingEventFilter

  def withSource(newSource: String): LoggingEventFilter

  def withMessageContains(newMessageContains: String): LoggingEventFilter

  def withMessageRegex(newMessageRegex: String): LoggingEventFilter

  def withCause[A <: Throwable: ClassTag]: LoggingEventFilter

  def withCustom(newCustom: PartialFunction[LoggingEvent, Boolean]): LoggingEventFilter

  def matches(event: LoggingEvent): Boolean

  /**
   * Apply this filter while executing the given code block. Care is taken to
   * remove the filter when the block is finished or aborted.
   */
  def intercept[T](code: => T)(implicit system: ActorSystem[_]): T

  /**
   * Apply this filter while executing the given code block. Care is taken to
   * remove the filter when the block is finished or aborted.
   */
  def interceptLogger[T](loggerName: String)(code: => T)(implicit system: ActorSystem[_]): T
}

/**
 * Facilities for selectively matching expected events from logging.
 */
object LoggingEventFilter {

  def empty: LoggingEventFilter = LoggingEventFilterImpl.empty

  def messageContains(str: String): LoggingEventFilter =
    empty.withMessageContains(str)

  def trace(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.TRACE)

  def debug(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.DEBUG)

  def info(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.INFO)

  def warn(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.WARN)

  def warn[A <: Throwable: ClassTag]: LoggingEventFilter =
    empty.withLogLevel(Level.WARN).withCause[A]

  def error(messageIncludes: String): LoggingEventFilter =
    messageContains(messageIncludes).withLogLevel(Level.ERROR)

  def error[A <: Throwable: ClassTag]: LoggingEventFilter =
    empty.withLogLevel(Level.ERROR).withCause[A]

  /**
   * Create a custom event filter. The filter will affect those events for
   * which the supplied partial function is defined and returns
   * `true`.
   */
  def custom(test: PartialFunction[LoggingEvent, Boolean]): LoggingEventFilter =
    empty.withCustom(test)

  def deadLetters(): LoggingEventFilter =
    empty.withLogLevel(Level.INFO).withMessageRegex(".*was not delivered.*dead letters encountered.*")
}
