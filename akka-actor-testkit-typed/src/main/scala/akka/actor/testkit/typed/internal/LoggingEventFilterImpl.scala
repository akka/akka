/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.util.matching.Regex

import akka.actor.testkit.typed.LoggingEvent
import akka.actor.testkit.typed.scaladsl.LoggingEventFilter
import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.testkit.TestKit
import akka.testkit.TestKitExtension
import org.slf4j.event.Level

/**
 * INTERNAL API
 */
@InternalApi private[akka] object LoggingEventFilterImpl {
  def empty: LoggingEventFilterImpl = new LoggingEventFilterImpl(1, None, None, None, None, None, None)
}

/**
 * INTERNAL API
 *
 * Facilities for selectively filtering out expected events from logging so
 * that you can keep your test run’s console output clean and do not miss real
 * error messages.
 *
 * See the companion object for convenient factory methods.
 *
 * If the `occurrences` is set to Int.MaxValue, no tracking is done.
 */
@InternalApi private[akka] final case class LoggingEventFilterImpl(
    occurrences: Int,
    logLevel: Option[Level],
    source: Option[String],
    messageContains: Option[String],
    messageRegex: Option[Regex],
    cause: Option[Class[Throwable]],
    custom: Option[PartialFunction[LoggingEvent, Boolean]])
    extends LoggingEventFilter {

  @volatile // JMM does not guarantee visibility for non-final fields
  private var todo = occurrences

  /**
   * This method decides whether to filter the event (<code>true</code>) or not
   * (<code>false</code>).
   */
  def matches(event: LoggingEvent): Boolean = {
    logLevel.forall(_ == event.level) &&
    source.forall(_ == sourceOrEmpty(event)) &&
    messageContains.forall(messageOrEmpty(event).contains) &&
    messageRegex.forall(_.findFirstIn(messageOrEmpty(event)).isDefined) &&
    cause.forall(c => event.throwable.isDefined && c.isInstance(event.throwable.get)) &&
    custom.forall(pf => pf.isDefinedAt(event) && pf(event))
  }

  private def messageOrEmpty(event: LoggingEvent): String =
    if (event.message == null) "" else event.message

  private def sourceOrEmpty(event: LoggingEvent): String =
    event.mdc.getOrElse("akkaSource", "")

  def apply(event: LoggingEvent): Boolean = {
    if (matches(event)) {
      if (todo != Int.MaxValue) todo -= 1
      true
    } else false
  }

  def awaitDone(max: Duration): Boolean = {
    if (todo != Int.MaxValue && todo > 0) TestKit.awaitCond(todo <= 0, max, noThrow = true)
    todo == Int.MaxValue || todo == 0
  }

  /**
   * Assert that this filter has matched as often as requested by its
   * `occurrences` parameter specifies.
   */
  def assertDone(max: Duration): Unit =
    assert(
      awaitDone(max),
      if (todo > 0) s"$todo messages outstanding on $this"
      else s"received ${-todo} excess messages on $this")

  /**
   * Apply this filter while executing the given code block. Care is taken to
   * remove the filter when the block is finished or aborted.
   */
  override def intercept[T](code: => T)(implicit system: ActorSystem[_]): T = {
    interceptLogger("")(code)
  }

  /**
   * Apply this filter while executing the given code block. Care is taken to
   * remove the filter when the block is finished or aborted.
   */
  override def interceptLogger[T](loggerName: String)(code: => T)(implicit system: ActorSystem[_]): T = {
    TestAppender.setupTestAppender(loggerName)
    TestAppender.addFilter(loggerName, this)
    // FIXME #26537 other setting for leeway
    import akka.actor.typed.scaladsl.adapter._
    val leeway = TestKitExtension(system.toUntyped).TestEventFilterLeeway
    try {
      val result = code
      if (!awaitDone(leeway))
        if (todo > 0)
          throw new AssertionError(s"timeout ($leeway) waiting for $todo messages on $this")
        else
          throw new AssertionError(s"received ${-todo} excess messages on $this")
      result
    } finally {
      todo = occurrences
      TestAppender.removeFilter(loggerName, this)
    }
  }

  override def withOccurrences(newOccurrences: Int): LoggingEventFilter =
    copy(occurrences = newOccurrences)

  override def withLogLevel(newLogLevel: Level): LoggingEventFilter =
    copy(logLevel = Option(newLogLevel))

  override def withSource(newSource: String): LoggingEventFilter =
    copy(source = Option(newSource))

  override def withMessageContains(newMessageContains: String): LoggingEventFilter =
    copy(messageContains = Option(newMessageContains))

  def withMessageRegex(newMessageRegex: String): LoggingEventFilter =
    copy(messageRegex = Option(new Regex(newMessageRegex)))

  override def withCause[A <: Throwable: ClassTag]: LoggingEventFilter = {
    val causeClass = implicitly[ClassTag[A]].runtimeClass.asInstanceOf[Class[Throwable]]
    copy(cause = Option(causeClass))
  }

  override def withCustom(newCustom: PartialFunction[LoggingEvent, Boolean]): LoggingEventFilter =
    copy(custom = Option(newCustom))

}
