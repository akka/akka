/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import scala.util.matching.Regex
import akka.actor.{ DeadLetter, ActorSystem, Terminated, UnhandledMessage }
import akka.dispatch.{ SystemMessage, Terminate }
import akka.event.Logging.{ Warning, LogEvent, InitializeLogger, Info, Error, Debug, LoggerInitialized }
import akka.event.Logging
import java.lang.{ Iterable ⇒ JIterable }
import scala.collection.JavaConverters
import akka.util.Duration

/**
 * Implementation helpers of the EventFilter facilities: send `Mute`
 * to the TestEventListener to install a filter, and `UnMute` to
 * deinstall it.
 *
 * You should always prefer the filter methods in the package object
 * (see [[akka.testkit]] `filterEvents` and `filterException`) or on the
 * EventFilter implementations.
 */
sealed trait TestEvent

/**
 * Implementation helpers of the EventFilter facilities: send <code>Mute</code>
 * to the TestEventFilter to install a filter, and <code>UnMute</code> to
 * deinstall it.
 *
 * You should always prefer the filter methods in the package object
 * (see [[akka.testkit]] `filterEvents` and `filterException`) or on the
 * EventFilter implementations.
 */
object TestEvent {
  object Mute {
    def apply(filter: EventFilter, filters: EventFilter*): Mute = new Mute(filter +: filters.toSeq)
  }
  case class Mute(filters: Seq[EventFilter]) extends TestEvent {
    /**
     * Java API
     */
    def this(filters: JIterable[EventFilter]) = this(JavaConverters.iterableAsScalaIterableConverter(filters).asScala.toSeq)
  }
  object UnMute {
    def apply(filter: EventFilter, filters: EventFilter*): UnMute = new UnMute(filter +: filters.toSeq)
  }
  case class UnMute(filters: Seq[EventFilter]) extends TestEvent {
    /**
     * Java API
     */
    def this(filters: JIterable[EventFilter]) = this(JavaConverters.iterableAsScalaIterableConverter(filters).asScala.toSeq)
  }
}

/**
 * Facilities for selectively filtering out expected events from logging so
 * that you can keep your test run’s console output clean and do not miss real
 * error messages.
 *
 * See the companion object for convenient factory methods.
 *
 * If the `occurrences` is set to Int.MaxValue, no tracking is done.
 */
abstract class EventFilter(occurrences: Int) {

  @volatile // JMM does not guarantee visibility for non-final fields
  private var todo = occurrences

  /**
   * This method decides whether to filter the event (<code>true</code>) or not
   * (<code>false</code>).
   */
  protected def matches(event: LogEvent): Boolean

  final def apply(event: LogEvent): Boolean = {
    if (matches(event)) {
      if (todo != Int.MaxValue) todo -= 1
      true
    } else false
  }

  def awaitDone(max: Duration): Boolean = {
    if (todo != Int.MaxValue && todo > 0) TestKit.awaitCond(todo == 0, max, noThrow = true)
    todo == Int.MaxValue || todo == 0
  }

  /**
   * Apply this filter while executing the given code block. Care is taken to
   * remove the filter when the block is finished or aborted.
   */
  def intercept[T](code: ⇒ T)(implicit system: ActorSystem): T = {
    system.eventStream publish TestEvent.Mute(this)
    val leeway = TestKitExtension(system).TestEventFilterLeeway
    try {
      val result = code
      if (!awaitDone(leeway))
        if (todo > 0)
          throw new AssertionError("Timeout (" + leeway + ") waiting for " + todo + " messages on " + this)
        else
          throw new AssertionError("Received " + (-todo) + " messages too many on " + this)
      result
    } finally system.eventStream publish TestEvent.UnMute(this)
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
        case Left(s)  ⇒ if (complete) msgstr == s else msgstr.startsWith(s)
        case Right(p) ⇒ p.findFirstIn(msgstr).isDefined
      })
  }
}

/**
 * Facilities for selectively filtering out expected events from logging so
 * that you can keep your test run’s console output clean and do not miss real
 * error messages.
 *
 * '''Also have a look at the [[akka.testkit]] package object’s `filterEvents` and
 * `filterException` methods.'''
 *
 * The source filters do accept `Class[_]` arguments, matching any
 * object which is an instance of the given class, e.g.
 *
 * {{{
 * EventFilter.info(source = classOf[MyActor]) // will match Info events from any MyActor instance
 * }}}
 *
 * The message object will be converted to a string before matching (`"null"` if it is `null`).
 */
object EventFilter {

  /**
   * Create a filter for Error events. Give up to one of <code>start</code> and <code>pattern</code>:
   *
   * {{{
   * EventFilter[MyException]()                                         // filter only on exception type
   * EventFilter[MyException]("message")                                // filter on exactly matching message
   * EventFilter[MyException](source = obj)                             // filter on event source
   * EventFilter[MyException](start = "Expected")                       // filter on start of message
   * EventFilter[MyException](source = obj, pattern = "weird.*message") // filter on pattern and message
   * }}}
   *
   * ''Please note that filtering on the `source` being
   * `null` does NOT work (passing `null` disables the
   * source filter).''
   */
  def apply[A <: Throwable: Manifest](message: String = null, source: String = null, start: String = "", pattern: String = null, occurrences: Int = Int.MaxValue): EventFilter =
    ErrorFilter(manifest[A].erasure, Option(source),
      if (message ne null) Left(message) else Option(pattern) map (new Regex(_)) toRight start,
      message ne null)(occurrences)

  /**
   * Create a filter for Error events which do not have a cause set (i.e. use
   * implicitly supplied Logging.Error.NoCause). See apply() for more details.
   */
  def error(message: String = null, source: String = null, start: String = "", pattern: String = null, occurrences: Int = Int.MaxValue): EventFilter =
    ErrorFilter(Logging.Error.NoCause.getClass, Option(source),
      if (message ne null) Left(message) else Option(pattern) map (new Regex(_)) toRight start,
      message ne null)(occurrences)

  /**
   * Create a filter for Warning events. Give up to one of <code>start</code> and <code>pattern</code>:
   *
   * {{{
   * EventFilter.warning()                                         // filter only on exception type
   * EventFilter.warning(source = obj)                             // filter on event source
   * EventFilter.warning(start = "Expected")                       // filter on start of message
   * EventFilter.warning(source = obj, pattern = "weird.*message") // filter on pattern and message
   * }}}
   *
   * ''Please note that filtering on the `source` being
   * `null` does NOT work (passing `null` disables the
   * source filter).''
   */
  def warning(message: String = null, source: String = null, start: String = "", pattern: String = null, occurrences: Int = Int.MaxValue): EventFilter =
    WarningFilter(Option(source),
      if (message ne null) Left(message) else Option(pattern) map (new Regex(_)) toRight start,
      message ne null)(occurrences)

  /**
   * Create a filter for Info events. Give up to one of <code>start</code> and <code>pattern</code>:
   *
   * {{{
   * EventFilter.info()                                         // filter only on exception type
   * EventFilter.info(source = obj)                             // filter on event source
   * EventFilter.info(start = "Expected")                       // filter on start of message
   * EventFilter.info(source = obj, pattern = "weird.*message") // filter on pattern and message
   * }}}
   *
   * ''Please note that filtering on the `source` being
   * `null` does NOT work (passing `null` disables the
   * source filter).''
   */
  def info(message: String = null, source: String = null, start: String = "", pattern: String = null, occurrences: Int = Int.MaxValue): EventFilter =
    InfoFilter(Option(source),
      if (message ne null) Left(message) else Option(pattern) map (new Regex(_)) toRight start,
      message ne null)(occurrences)

  /**
   * Create a filter for Debug events. Give up to one of <code>start</code> and <code>pattern</code>:
   *
   * {{{
   * EventFilter.debug()                                         // filter only on exception type
   * EventFilter.debug(source = obj)                             // filter on event source
   * EventFilter.debug(start = "Expected")                       // filter on start of message
   * EventFilter.debug(source = obj, pattern = "weird.*message") // filter on pattern and message
   * }}}
   *
   * ''Please note that filtering on the `source` being
   * `null` does NOT work (passing `null` disables the
   * source filter).''
   */
  def debug(message: String = null, source: String = null, start: String = "", pattern: String = null, occurrences: Int = Int.MaxValue): EventFilter =
    DebugFilter(Option(source),
      if (message ne null) Left(message) else Option(pattern) map (new Regex(_)) toRight start,
      message ne null)(occurrences)

  /**
   * Create a custom event filter. The filter will affect those events for
   * which the supplied partial function is defined and returns
   * `true`.
   *
   * {{{
   * EventFilter.custom {
   *   case Warning(ref, "my warning") if ref == actor || ref == null => true
   * }
   * }}}
   */
  def custom(test: PartialFunction[LogEvent, Boolean], occurrences: Int = Int.MaxValue): EventFilter =
    CustomEventFilter(test)(occurrences)
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
case class ErrorFilter(
  throwable: Class[_],
  override val source: Option[String],
  override val message: Either[String, Regex],
  override val complete: Boolean)(occurrences: Int) extends EventFilter(occurrences) {

  def matches(event: LogEvent) = {
    event match {
      case Error(cause, src, _, msg) if throwable isInstance cause ⇒
        (msg == null && cause.getMessage == null && cause.getStackTrace.length == 0) ||
          doMatch(src, msg) || doMatch(src, cause.getMessage)
      case _ ⇒ false
    }
  }

  /**
   * Java API
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
  def this(throwable: Class[_], source: String, message: String, pattern: Boolean, complete: Boolean, occurrences: Int) =
    this(throwable, Option(source),
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
case class WarningFilter(
  override val source: Option[String],
  override val message: Either[String, Regex],
  override val complete: Boolean)(occurrences: Int) extends EventFilter(occurrences) {

  def matches(event: LogEvent) = {
    event match {
      case Warning(src, _, msg) ⇒ doMatch(src, msg)
      case _                    ⇒ false
    }
  }

  /**
   * Java API
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
    this(Option(source),
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
case class InfoFilter(
  override val source: Option[String],
  override val message: Either[String, Regex],
  override val complete: Boolean)(occurrences: Int) extends EventFilter(occurrences) {

  def matches(event: LogEvent) = {
    event match {
      case Info(src, _, msg) ⇒ doMatch(src, msg)
      case _                 ⇒ false
    }
  }

  /**
   * Java API
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
    this(Option(source),
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
case class DebugFilter(
  override val source: Option[String],
  override val message: Either[String, Regex],
  override val complete: Boolean)(occurrences: Int) extends EventFilter(occurrences) {

  def matches(event: LogEvent) = {
    event match {
      case Debug(src, _, msg) ⇒ doMatch(src, msg)
      case _                  ⇒ false
    }
  }

  /**
   * Java API
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
    this(Option(source),
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
case class CustomEventFilter(test: PartialFunction[LogEvent, Boolean])(occurrences: Int) extends EventFilter(occurrences) {
  def matches(event: LogEvent) = {
    test.isDefinedAt(event) && test(event)
  }
}

/**
 * EventListener for running tests, which allows selectively filtering out
 * expected messages. To use it, include something like this into
 * <code>akka.test.conf</code> and run your tests with system property
 * <code>"akka.mode"</code> set to <code>"test"</code>:
 *
 * <pre><code>
 * akka {
 *   event-handlers = ["akka.testkit.TestEventListener"]
 * }
 * </code></pre>
 */
class TestEventListener extends Logging.DefaultLogger {
  import TestEvent._

  var filters: List[EventFilter] = Nil

  override def receive = {
    case InitializeLogger(bus) ⇒
      Seq(classOf[Mute], classOf[UnMute], classOf[DeadLetter], classOf[UnhandledMessage]) foreach (bus.subscribe(context.self, _))
      sender ! LoggerInitialized
    case Mute(filters)   ⇒ filters foreach addFilter
    case UnMute(filters) ⇒ filters foreach removeFilter
    case event: LogEvent ⇒ if (!filter(event)) print(event)
    case DeadLetter(msg: SystemMessage, _, rcp) ⇒
      if (!msg.isInstanceOf[Terminate]) {
        val event = Warning(rcp.path.toString, rcp.getClass, "received dead system message: " + msg)
        if (!filter(event)) print(event)
      }
    case DeadLetter(msg, snd, rcp) ⇒
      if (!msg.isInstanceOf[Terminated]) {
        val event = Warning(rcp.path.toString, rcp.getClass, "received dead letter from " + snd + ": " + msg)
        if (!filter(event)) print(event)
      }
    case UnhandledMessage(msg, sender, rcp) ⇒
      val event = Warning(rcp.path.toString, rcp.getClass, "unhandled message from " + sender + ": " + msg)
      if (!filter(event)) print(event)
    case m ⇒ print(Debug(context.system.name, this.getClass, m))
  }

  def filter(event: LogEvent): Boolean = filters exists (f ⇒ try { f(event) } catch { case e: Exception ⇒ false })

  def addFilter(filter: EventFilter): Unit = filters ::= filter

  def removeFilter(filter: EventFilter) {
    @scala.annotation.tailrec
    def removeFirst(list: List[EventFilter], zipped: List[EventFilter] = Nil): List[EventFilter] = list match {
      case head :: tail if head == filter ⇒ tail.reverse_:::(zipped)
      case head :: tail                   ⇒ removeFirst(tail, head :: zipped)
      case Nil                            ⇒ filters // filter not found, just return original list
    }
    filters = removeFirst(filters)
  }

}
