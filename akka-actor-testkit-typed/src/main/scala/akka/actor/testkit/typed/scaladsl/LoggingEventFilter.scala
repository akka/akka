/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.util.matching.Regex

import akka.actor.testkit.typed.LoggingEvent
import akka.actor.testkit.typed.internal.TestAppender
import akka.actor.typed.ActorSystem
import akka.testkit.TestKit
import akka.testkit.TestKitExtension
import org.slf4j.event.Level

// FIXME #26537 this is a close copy of classic EventFilter so far. Design a better scaladsl and javadsl API.

/**
 * Facilities for selectively filtering out expected events from logging so
 * that you can keep your test run’s console output clean and do not miss real
 * error messages.
 *
 * See the companion object for convenient factory methods.
 *
 * If the `occurrences` is set to Int.MaxValue, no tracking is done.
 */
abstract class LoggingEventFilter(occurrences: Int) {

  @volatile // JMM does not guarantee visibility for non-final fields
  private var todo = occurrences

  /**
   * This method decides whether to filter the event (<code>true</code>) or not
   * (<code>false</code>).
   */
  protected def matches(event: LoggingEvent): Boolean

  final def apply(event: LoggingEvent): Boolean = {
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
  def intercept[T](code: => T)(implicit system: ActorSystem[_]): T = {
    interceptLogger("")(code)
  }

  /**
   * Apply this filter while executing the given code block. Care is taken to
   * remove the filter when the block is finished or aborted.
   */
  def interceptLogger[T](loggerName: String)(code: => T)(implicit system: ActorSystem[_]): T = {
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
      TestAppender.removeFilter(loggerName, this)
    }
  }

  /*
   * these default values are just there for easier subclassing
   */
  protected val source: Option[String] = None
  protected val message: Either[String, Regex] = Left("")
  protected val complete: Boolean = false

  /**
   * internal implementation helper, no guaranteed API
   */
  protected def doMatch(src: String, msg: Any) = {
    val msgstr = if (msg != null) msg.toString else "null"
    (source.isDefined && source.get == src || source.isEmpty) &&
    (message match {
      case Left(s)  => if (complete) msgstr == s else msgstr.startsWith(s)
      case Right(p) => p.findFirstIn(msgstr).isDefined
    })
  }

}

/**
 * Facilities for selectively filtering out expected events from logging so
 * that you can keep your test run’s console output clean and do not miss real
 * error messages.
 *
 * '''Also have a look at the `akka.testkit` package object’s `filterEvents` and
 * `filterException` methods.'''
 *
 * The source filters do accept `Class[_]` arguments, matching any
 * object which is an instance of the given class, e.g.
 *
 * {{{
 * LoggingEventFilter.info(source = classOf[MyActor]) // will match Info events from any MyActor instance
 * }}}
 *
 * The message object will be converted to a string before matching (`"null"` if it is `null`).
 */
object LoggingEventFilter {

  /**
   * Create a filter for Error events. Give up to one of <code>start</code> and <code>pattern</code>:
   *
   * {{{
   * LoggingEventFilter[MyException]()                                         // filter only on exception type
   * LoggingEventFilter[MyException]("message")                                // filter on exactly matching message
   * LoggingEventFilter[MyException](source = obj)                             // filter on event source
   * LoggingEventFilter[MyException](start = "Expected")                       // filter on start of message
   * LoggingEventFilter[MyException](source = obj, pattern = "weird.*message") // filter on pattern and message
   * }}}
   *
   * ''Please note that filtering on the `source` being
   * `null` does NOT work (passing `null` disables the
   * source filter).''
   */
  def apply[A <: Throwable: ClassTag](
      message: String = null,
      source: String = null,
      start: String = "",
      pattern: String = null,
      occurrences: Int = Int.MaxValue): LoggingEventFilter =
    ErrorFilter(
      Some(implicitly[ClassTag[A]].runtimeClass),
      Option(source),
      if (message ne null) Left(message) else Option(pattern).map(new Regex(_)).toRight(start),
      message ne null)(occurrences)

  /**
   * Create a filter for Error events. See apply() for more details.
   */
  def error(
      message: String = null,
      source: String = null,
      start: String = "",
      pattern: String = null,
      occurrences: Int = Int.MaxValue): LoggingEventFilter =
    ErrorFilter(
      None,
      Option(source),
      if (message ne null) Left(message) else Option(pattern).map(new Regex(_)).toRight(start),
      message ne null)(occurrences)

  /**
   * Create a filter for Warning events. Give up to one of <code>start</code> and <code>pattern</code>:
   *
   * {{{
   * LoggingEventFilter.warning()                                         // filter only on warning event
   * LoggingEventFilter.warning(source = obj)                             // filter on event source
   * LoggingEventFilter.warning(start = "Expected")                       // filter on start of message
   * LoggingEventFilter.warning(source = obj, pattern = "weird.*message") // filter on pattern and message
   * }}}
   *
   * ''Please note that filtering on the `source` being
   * `null` does NOT work (passing `null` disables the
   * source filter).''
   */
  def warning(
      message: String = null,
      source: String = null,
      start: String = "",
      pattern: String = null,
      occurrences: Int = Int.MaxValue): LoggingEventFilter =
    WarningFilter(
      Option(source),
      if (message ne null) Left(message) else Option(pattern).map(new Regex(_)).toRight(start),
      message ne null)(occurrences)

  /**
   * Create a filter for Info events. Give up to one of <code>start</code> and <code>pattern</code>:
   *
   * {{{
   * LoggingEventFilter.info()                                         // filter only on info event
   * LoggingEventFilter.info(source = obj)                             // filter on event source
   * LoggingEventFilter.info(start = "Expected")                       // filter on start of message
   * LoggingEventFilter.info(source = obj, pattern = "weird.*message") // filter on pattern and message
   * }}}
   *
   * ''Please note that filtering on the `source` being
   * `null` does NOT work (passing `null` disables the
   * source filter).''
   */
  def info(
      message: String = null,
      source: String = null,
      start: String = "",
      pattern: String = null,
      occurrences: Int = Int.MaxValue): LoggingEventFilter =
    InfoFilter(
      Option(source),
      if (message ne null) Left(message) else Option(pattern).map(new Regex(_)).toRight(start),
      message ne null)(occurrences)

  /**
   * Create a filter for Debug events. Give up to one of <code>start</code> and <code>pattern</code>:
   *
   * {{{
   * LoggingEventFilter.debug()                                         // filter only on debug type
   * LoggingEventFilter.debug(source = obj)                             // filter on event source
   * LoggingEventFilter.debug(start = "Expected")                       // filter on start of message
   * LoggingEventFilter.debug(source = obj, pattern = "weird.*message") // filter on pattern and message
   * }}}
   *
   * ''Please note that filtering on the `source` being
   * `null` does NOT work (passing `null` disables the
   * source filter).''
   */
  def debug(
      message: String = null,
      source: String = null,
      start: String = "",
      pattern: String = null,
      occurrences: Int = Int.MaxValue): LoggingEventFilter =
    DebugFilter(
      Option(source),
      if (message ne null) Left(message) else Option(pattern).map(new Regex(_)).toRight(start),
      message ne null)(occurrences)

  /**
   * Create a custom event filter. The filter will affect those events for
   * which the supplied partial function is defined and returns
   * `true`.
   *
   * {{{
   * LoggingEventFilter.custom {
   *   case Warning(ref, "my warning") if ref == actor || ref == null => true
   * }
   * }}}
   */
  def custom(test: PartialFunction[LoggingEvent, Boolean], occurrences: Int = Int.MaxValue): LoggingEventFilter =
    CustomEventFilter(test)(occurrences)

  def deadLetters(occurrences: Int = Int.MaxValue): LoggingEventFilter =
    info(pattern = ".*was not delivered.*dead letters encountered.*", occurrences = occurrences)
}

/**
 * Filter which matches Error events, if they satisfy the given criteria:
 * <ul>
 * <li><code>throwable</code> applies an upper bound on the type of exception contained in the Error event</li>
 * <li><code>source</code>, if given, applies a filter on the event’s origin</li>
 * <li><code>message</code> applies a filter on the event’s message (either
 *   with String.startsWith or Regex.findFirstIn().isDefined); if the message
 *   itself does not match, the match is retried with the contained Exception’s
 *   message; if both are <code>null</code>, the filter always matches if at
 *   the same time the Exception’s stack trace is empty (this catches
 *   JVM-omitted “fast-throw” exceptions)</li>
 * </ul>
 * If you want to match all Error events, the most efficient is to use <code>Left("")</code>.
 */
final case class ErrorFilter(
    throwable: Option[Class[_]],
    override val source: Option[String],
    override val message: Either[String, Regex],
    override val complete: Boolean)(occurrences: Int)
    extends LoggingEventFilter(occurrences) {

  def matches(event: LoggingEvent) = {
    event.level == Level.ERROR &&
    (throwable.isEmpty || throwable.get.isInstance(event.throwable.orNull)) &&
    ((event.message == null && event.throwable
      .map(_.getMessage)
      .isEmpty && event.throwable.map(_.getStackTrace.length).getOrElse(0) == 0) ||
    doMatch(event.mdc.getOrElse("akkaSource", ""), event.message) || (event.throwable.nonEmpty && doMatch(
      event.mdc.getOrElse("akkaSource", ""),
      event.throwable.get.getMessage)))
  }

  /**
   * Java API: create an ErrorFilter
   *
   * @param source
   *   apply this filter only to events from the given source; do not filter on source if this is given as <code>null</code>
   * @param message
   *   apply this filter only to events whose message matches; do not filter on message if this is given as <code>null</code>
   * @param pattern
   *   if <code>false</code>, the message string must start with the given
   *   string, otherwise the <code>message</code> argument is treated as
   *   regular expression which is matched against the message (may match only
   *   a substring to filter)
   * @param complete
   *   whether the event’s message must match the given message string or pattern completely
   */
  def this(
      throwable: Class[_],
      source: String,
      message: String,
      pattern: Boolean,
      complete: Boolean,
      occurrences: Int) =
    this(
      Option(throwable),
      Option(source),
      if (message eq null) Left("")
      else if (pattern) Right(new Regex(message))
      else Left(message),
      complete)(occurrences)

  /**
   * Java API: filter only on the given type of exception
   */
  def this(throwable: Class[_]) = this(throwable, null, null, false, false, Int.MaxValue)

}

/**
 * Filter which matches Warning events, if they satisfy the given criteria:
 * <ul>
 * <li><code>source</code>, if given, applies a filter on the event’s origin</li>
 * <li><code>message</code> applies a filter on the event’s message (either with String.startsWith or Regex.findFirstIn().isDefined)</li>
 * </ul>
 * If you want to match all Warning events, the most efficient is to use <code>Left("")</code>.
 */
final case class WarningFilter(
    override val source: Option[String],
    override val message: Either[String, Regex],
    override val complete: Boolean)(occurrences: Int)
    extends LoggingEventFilter(occurrences) {

  def matches(event: LoggingEvent) = {
    event.level == Level.WARN && doMatch(event.mdc.getOrElse("akkaSource", ""), event.message)
  }

  /**
   * Java API: create a WarningFilter
   *
   * @param source
   *   apply this filter only to events from the given source; do not filter on source if this is given as <code>null</code>
   * @param message
   *   apply this filter only to events whose message matches; do not filter on message if this is given as <code>null</code>
   * @param pattern
   *   if <code>false</code>, the message string must start with the given
   *   string, otherwise the <code>message</code> argument is treated as
   *   regular expression which is matched against the message (may match only
   *   a substring to filter)
   * @param complete
   *   whether the event’s message must match the given message string or pattern completely
   */
  def this(source: String, message: String, pattern: Boolean, complete: Boolean, occurrences: Int) =
    this(
      Option(source),
      if (message eq null) Left("")
      else if (pattern) Right(new Regex(message))
      else Left(message),
      complete)(occurrences)
}

/**
 * Filter which matches Info events, if they satisfy the given criteria:
 * <ul>
 * <li><code>source</code>, if given, applies a filter on the event’s origin</li>
 * <li><code>message</code> applies a filter on the event’s message (either with String.startsWith or Regex.findFirstIn().isDefined)</li>
 * </ul>
 * If you want to match all Info events, the most efficient is to use <code>Left("")</code>.
 */
final case class InfoFilter(
    override val source: Option[String],
    override val message: Either[String, Regex],
    override val complete: Boolean)(occurrences: Int)
    extends LoggingEventFilter(occurrences) {

  def matches(event: LoggingEvent) = {
    event.level == Level.INFO && doMatch(event.mdc.getOrElse("akkaSource", ""), event.message)
  }

  /**
   * Java API: create an InfoFilter
   *
   * @param source
   *   apply this filter only to events from the given source; do not filter on source if this is given as <code>null</code>
   * @param message
   *   apply this filter only to events whose message matches; do not filter on message if this is given as <code>null</code>
   * @param pattern
   *   if <code>false</code>, the message string must start with the given
   *   string, otherwise the <code>message</code> argument is treated as
   *   regular expression which is matched against the message (may match only
   *   a substring to filter)
   * @param complete
   *   whether the event’s message must match the given message string or pattern completely
   */
  def this(source: String, message: String, pattern: Boolean, complete: Boolean, occurrences: Int) =
    this(
      Option(source),
      if (message eq null) Left("")
      else if (pattern) Right(new Regex(message))
      else Left(message),
      complete)(occurrences)
}

/**
 * Filter which matches Debug events, if they satisfy the given criteria:
 * <ul>
 * <li><code>source</code>, if given, applies a filter on the event’s origin</li>
 * <li><code>message</code> applies a filter on the event’s message (either with String.startsWith or Regex.findFirstIn().isDefined)</li>
 * </ul>
 * If you want to match all Debug events, the most efficient is to use <code>Left("")</code>.
 */
final case class DebugFilter(
    override val source: Option[String],
    override val message: Either[String, Regex],
    override val complete: Boolean)(occurrences: Int)
    extends LoggingEventFilter(occurrences) {

  def matches(event: LoggingEvent) = {
    event.level == Level.DEBUG && doMatch(event.mdc.getOrElse("akkaSource", ""), event.message)
  }

  /**
   * Java API: create a DebugFilter
   *
   * @param source
   *   apply this filter only to events from the given source; do not filter on source if this is given as <code>null</code>
   * @param message
   *   apply this filter only to events whose message matches; do not filter on message if this is given as <code>null</code>
   * @param pattern
   *   if <code>false</code>, the message string must start with the given
   *   string, otherwise the <code>message</code> argument is treated as
   *   regular expression which is matched against the message (may match only
   *   a substring to filter)
   * @param complete
   *   whether the event’s message must match the given message string or pattern completely
   */
  def this(source: String, message: String, pattern: Boolean, complete: Boolean, occurrences: Int) =
    this(
      Option(source),
      if (message eq null) Left("")
      else if (pattern) Right(new Regex(message))
      else Left(message),
      complete)(occurrences)
}

/**
 * Custom event filter when the others do not fit the bill.
 *
 * If the partial function is defined and returns true, filter the event.
 */
final case class CustomEventFilter(test: PartialFunction[LoggingEvent, Boolean])(occurrences: Int)
    extends LoggingEventFilter(occurrences) {
  def matches(event: LoggingEvent) = {
    test.isDefinedAt(event) && test(event)
  }
}
