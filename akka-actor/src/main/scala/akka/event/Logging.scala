/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor._
import akka.AkkaException
import akka.actor.ActorSystem.Settings
import akka.util.ReflectiveAccess
import akka.config.ConfigurationException
import akka.util.ReentrantGuard
import akka.util.duration._
import akka.util.Timeout
import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NoStackTrace
import java.util.concurrent.TimeoutException
import akka.dispatch.Await

object LoggingBus {
  implicit def fromActorSystem(system: ActorSystem): LoggingBus = system.eventStream
}

/**
 * This trait brings log level handling to the EventStream: it reads the log
 * levels for the initial logging (StandardOutLogger) and the loggers & level
 * for after-init logging, possibly keeping the StandardOutLogger enabled if
 * it is part of the configured loggers. All configured loggers are treated as
 * system services and managed by this trait, i.e. subscribed/unsubscribed in
 * response to changes of LoggingBus.logLevel.
 */
trait LoggingBus extends ActorEventBus {

  type Event >: Logging.LogEvent
  type Classifier >: Class[_]

  import Logging._

  private val guard = new ReentrantGuard
  private var loggers = Seq.empty[ActorRef]
  private var _logLevel: LogLevel = _

  /**
   * Query currently set log level. See object Logging for more information.
   */
  def logLevel = guard.withGuard { _logLevel }

  /**
   * Change log level: default loggers (i.e. from configuration file) are
   * subscribed/unsubscribed as necessary so that they listen to all levels
   * which are at least as severe as the given one. See object Logging for
   * more information.
   *
   * NOTE: if the StandardOutLogger is configured also as normal logger, it
   * will not participate in the automatic management of log level
   * subscriptions!
   */
  def setLogLevel(level: LogLevel): Unit = guard.withGuard {
    for {
      l ← AllLogLevels
      // subscribe if previously ignored and now requested
      if l > _logLevel && l <= level
      log ← loggers
    } subscribe(log, classFor(l))
    for {
      l ← AllLogLevels
      // unsubscribe if previously registered and now ignored
      if l <= _logLevel && l > level
      log ← loggers
    } unsubscribe(log, classFor(l))
    _logLevel = level
  }

  /**
   * Internal Akka use only
   */
  private[akka] def startStdoutLogger(config: Settings) {
    val level = levelFor(config.StdoutLogLevel) getOrElse {
      StandardOutLogger.print(Error(new EventHandlerException, simpleName(this), "unknown akka.stdout-loglevel " + config.StdoutLogLevel))
      ErrorLevel
    }
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(StandardOutLogger, classFor(l)))
    guard.withGuard {
      loggers = Seq(StandardOutLogger)
      _logLevel = level
    }
    publish(Debug(simpleName(this), "StandardOutLogger started"))
  }

  /**
   * Internal Akka use only
   */
  private[akka] def startDefaultLoggers(system: ActorSystemImpl) {
    val level = levelFor(system.settings.LogLevel) getOrElse {
      StandardOutLogger.print(Error(new EventHandlerException, simpleName(this), "unknown akka.stdout-loglevel " + system.settings.LogLevel))
      ErrorLevel
    }
    try {
      val defaultLoggers = system.settings.EventHandlers match {
        case Nil     ⇒ "akka.event.Logging$DefaultLogger" :: Nil
        case loggers ⇒ loggers
      }
      val myloggers = for {
        loggerName ← defaultLoggers
        if loggerName != StandardOutLoggerName
      } yield {
        try {
          ReflectiveAccess.getClassFor[Actor](loggerName) match {
            case Right(actorClass) ⇒ addLogger(system, actorClass, level)
            case Left(exception)   ⇒ throw exception
          }
        } catch {
          case e: Exception ⇒
            throw new ConfigurationException(
              "Event Handler specified in config can't be loaded [" + loggerName +
                "] due to [" + e.toString + "]", e)
        }
      }
      guard.withGuard {
        loggers = myloggers
        _logLevel = level
      }
      publish(Debug(simpleName(this), "Default Loggers started"))
      if (!(defaultLoggers contains StandardOutLoggerName)) {
        unsubscribe(StandardOutLogger)
      }
    } catch {
      case e: Exception ⇒
        System.err.println("error while starting up EventHandler")
        e.printStackTrace()
        throw new ConfigurationException("Could not start Event Handler due to [" + e.toString + "]")
    }
  }

  /**
   * Internal Akka use only
   */
  private[akka] def stopDefaultLoggers() {
    val level = _logLevel // volatile access before reading loggers
    if (!(loggers contains StandardOutLogger)) {
      AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(StandardOutLogger, classFor(l)))
      publish(Debug(simpleName(this), "shutting down: StandardOutLogger started"))
    }
    for {
      logger ← loggers
      if logger != StandardOutLogger
    } {
      // this is very necessary, else you get infinite loop with DeadLetter
      unsubscribe(logger)
      logger match {
        case ref: InternalActorRef ⇒ ref.stop()
        case _                     ⇒
      }
    }
    publish(Debug(simpleName(this), "all default loggers stopped"))
  }

  private def addLogger(system: ActorSystemImpl, clazz: Class[_ <: Actor], level: LogLevel): ActorRef = {
    val name = "log" + Extension(system).id() + "-" + simpleName(clazz)
    val actor = system.systemActorOf(Props(clazz), name)
    implicit val timeout = Timeout(3 seconds)
    import akka.pattern.ask
    val response = try Await.result(actor ? InitializeLogger(this), timeout.duration) catch {
      case _: TimeoutException ⇒
        publish(Warning(simpleName(this), "Logger " + name + " did not respond within " + timeout + " to InitializeLogger(bus)"))
    }
    if (response != LoggerInitialized)
      throw new LoggerInitializationException("Logger " + name + " did not respond with LoggerInitialized, sent instead " + response)
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(actor, classFor(l)))
    publish(Debug(simpleName(this), "logger " + name + " started"))
    actor
  }

}

trait LogSource[-T] {
  def genString(t: T): String
}

object LogSource {
  implicit val fromString: LogSource[String] = new LogSource[String] {
    def genString(s: String) = s
  }

  implicit val fromActor: LogSource[Actor] = new LogSource[Actor] {
    def genString(a: Actor) = a.self.path.toString
  }

  implicit val fromActorRef: LogSource[ActorRef] = new LogSource[ActorRef] {
    def genString(a: ActorRef) = a.path.toString
  }

  // this one unfortunately does not work as implicit, because existential types have some weird behavior
  val fromClass: LogSource[Class[_]] = new LogSource[Class[_]] {
    def genString(c: Class[_]) = simpleName(c)
  }
  implicit def fromAnyClass[T]: LogSource[Class[T]] = fromClass.asInstanceOf[LogSource[Class[T]]]

  def apply[T: LogSource](o: T) = implicitly[LogSource[T]].genString(o)

  def fromAnyRef(o: AnyRef): String =
    o match {
      case c: Class[_] ⇒ fromClass.genString(c)
      case a: Actor    ⇒ fromActor.genString(a)
      case a: ActorRef ⇒ fromActorRef.genString(a)
      case s: String   ⇒ s
      case x           ⇒ simpleName(x)
    }
}

/**
 * Main entry point for Akka logging: log levels and message types (aka
 * channels) defined for the main transport medium, the main event bus. The
 * recommended use is to obtain an implementation of the Logging trait with
 * suitable and efficient methods for generating log events:
 *
 * <pre><code>
 * val log = Logging(&lt;bus&gt;, &lt;source object&gt;)
 * ...
 * log.info("hello world!")
 * </code></pre>
 *
 * Loggers are attached to the level-specific channels <code>Error</code>,
 * <code>Warning</code>, <code>Info</code> and <code>Debug</code> as
 * appropriate for the configured (or set) log level. If you want to implement
 * your own, make sure to handle these four event types plus the <code>InitializeLogger</code>
 * message which is sent before actually attaching it to the logging bus.
 *
 * Logging is configured in <code>akka.conf</code> by setting (some of) the following:
 *
 * <pre><code>
 * akka {
 *   event-handlers = ["akka.slf4j.Slf4jEventHandler"] # for example
 *   loglevel = "INFO"        # used when normal logging ("event-handlers") has been started
 *   stdout-loglevel = "WARN" # used during application start-up until normal logging is available
 * }
 * </code></pre>
 */
object Logging {

  object Extension extends ExtensionKey[LogExt]

  class LogExt(system: ActorSystemImpl) extends Extension {
    private val loggerId = new AtomicInteger
    def id() = loggerId.incrementAndGet()
  }

  /**
   * Marker trait for annotating LogLevel, which must be Int after erasure.
   */
  trait LogLevelType

  /**
   * Log level in numeric form, used when deciding whether a certain log
   * statement should generate a log event. Predefined levels are ErrorLevel (1)
   * to DebugLevel (4). In case you want to add more levels, loggers need to
   * be subscribed to their event bus channels manually.
   */
  type LogLevel = Int with LogLevelType
  final val ErrorLevel = 1.asInstanceOf[Int with LogLevelType]
  final val WarningLevel = 2.asInstanceOf[Int with LogLevelType]
  final val InfoLevel = 3.asInstanceOf[Int with LogLevelType]
  final val DebugLevel = 4.asInstanceOf[Int with LogLevelType]

  /**
   * Returns the LogLevel associated with the given string,
   * valid inputs are upper or lowercase (not mixed) versions of:
   * "error", "warning", "info" and "debug"
   */
  def levelFor(s: String): Option[LogLevel] = s match {
    case "ERROR" | "error"     ⇒ Some(ErrorLevel)
    case "WARNING" | "warning" ⇒ Some(WarningLevel)
    case "INFO" | "info"       ⇒ Some(InfoLevel)
    case "DEBUG" | "debug"     ⇒ Some(DebugLevel)
    case unknown               ⇒ None
  }

  /**
   * Returns the LogLevel associated with the given event class.
   * Defaults to DebugLevel.
   */
  def levelFor(eventClass: Class[_ <: LogEvent]): LogLevel = {
    if (classOf[Error].isAssignableFrom(eventClass)) ErrorLevel
    else if (classOf[Warning].isAssignableFrom(eventClass)) WarningLevel
    else if (classOf[Info].isAssignableFrom(eventClass)) InfoLevel
    else if (classOf[Debug].isAssignableFrom(eventClass)) DebugLevel
    else DebugLevel
  }

  /**
   * Returns the event class associated with the given LogLevel
   */
  def classFor(level: LogLevel): Class[_ <: LogEvent] = level match {
    case ErrorLevel   ⇒ classOf[Error]
    case WarningLevel ⇒ classOf[Warning]
    case InfoLevel    ⇒ classOf[Info]
    case DebugLevel   ⇒ classOf[Debug]
  }

  // these type ascriptions/casts are necessary to avoid CCEs during construction while retaining correct type
  val AllLogLevels = Seq(ErrorLevel: AnyRef, WarningLevel, InfoLevel, DebugLevel).asInstanceOf[Seq[LogLevel]]

  val errorFormat = "[ERROR] [%s] [%s] [%s] %s\n%s".intern
  val errorFormatWithoutCause = "[ERROR] [%s] [%s] [%s] %s".intern
  val warningFormat = "[WARN] [%s] [%s] [%s] %s".intern
  val infoFormat = "[INFO] [%s] [%s] [%s] %s".intern
  val debugFormat = "[DEBUG] [%s] [%s] [%s] %s".intern

  /**
   * Obtain LoggingAdapter for the given event stream (system) and source object.
   * Note that there is an implicit conversion from [[akka.actor.ActorSystem]]
   * to [[akka.event.LoggingBus]].
   *
   * The source is used to identify the source of this logging channel and must have
   * a corresponding LogSource[T] instance in scope; by default these are
   * provided for Class[_], Actor, ActorRef and String types. The source
   * object is translated to a String according to the following rules:
   * <ul>
   * <li>if it is an Actor or ActorRef, its path is used</li>
   * <li>in case of a String it is used as is</li>
   * <li>in case of a class an approximation of its simpleName
   * <li>and in all other cases the simpleName of its class</li>
   * </ul>
   */
  def apply[T: LogSource](eventStream: LoggingBus, logSource: T): LoggingAdapter =
    new BusLogging(eventStream, implicitly[LogSource[T]].genString(logSource))

  /**
   * Java API: Obtain LoggingAdapter for the given system and source object. The
   * source object is used to identify the source of this logging channel. The source
   * object is translated to a String according to the following rules:
   * <ul>
   * <li>if it is an Actor or ActorRef, its path is used</li>
   * <li>in case of a String it is used as is</li>
   * <li>in case of a class an approximation of its simpleName
   * <li>and in all other cases the simpleName of its class</li>
   * </ul>
   */
  def getLogger(system: ActorSystem, logSource: AnyRef): LoggingAdapter = apply(system.eventStream, LogSource.fromAnyRef(logSource))

  /**
   * Java API: Obtain LoggingAdapter for the given event bus and source object. The
   * source object is used to identify the source of this logging channel.
   */
  def getLogger(bus: LoggingBus, logSource: AnyRef): LoggingAdapter = apply(bus, LogSource.fromAnyRef(logSource))

  /**
   * Artificial exception injected into Error events if no Throwable is
   * supplied; used for getting a stack dump of error locations.
   */
  class EventHandlerException extends AkkaException

  /**
   * Base type of LogEvents
   */
  sealed trait LogEvent {
    /**
     * The thread that created this log event
     */
    @transient
    val thread: Thread = Thread.currentThread

    /**
     * The LogLevel of this LogEvent
     */
    def level: LogLevel
  }

  /**
   * For ERROR Logging
   */
  case class Error(cause: Throwable, logSource: String, message: Any = "") extends LogEvent {
    def this(logSource: String, message: Any) = this(Error.NoCause, logSource, message)

    override def level = ErrorLevel
  }

  object Error {
    def apply(logSource: String, message: Any) = new Error(NoCause, logSource, message)

    /** Null Object used for errors without cause Throwable */
    object NoCause extends NoStackTrace
  }

  /**
   * For WARNING Logging
   */
  case class Warning(logSource: String, message: Any = "") extends LogEvent {
    override def level = WarningLevel
  }

  /**
   * For INFO Logging
   */
  case class Info(logSource: String, message: Any = "") extends LogEvent {
    override def level = InfoLevel
  }

  /**
   * For DEBUG Logging
   */
  case class Debug(logSource: String, message: Any = "") extends LogEvent {
    override def level = DebugLevel
  }

  /**
   * Message which is sent to each default logger (i.e. from configuration file)
   * after its creation but before attaching it to the logging bus. The logger
   * actor must handle this message, it can be used e.g. to register for more
   * channels. When done, the logger must respond with a LoggerInitialized
   * message. This is necessary to ensure that additional subscriptions are in
   * effect when the logging system finished starting.
   */
  case class InitializeLogger(bus: LoggingBus) extends NoSerializationVerificationNeeded

  /**
   * Response message each logger must send within 1 second after receiving the
   * InitializeLogger request. If initialization takes longer, send the reply
   * as soon as subscriptions are set-up.
   */
  case object LoggerInitialized

  /**
   * Java API to create a LoggerInitialized message.
   */
  def loggerInitialized() = LoggerInitialized

  class LoggerInitializationException(msg: String) extends AkkaException(msg)

  trait StdOutLogger {
    import java.text.SimpleDateFormat
    import java.util.Date

    val dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.S")

    def timestamp = dateFormat.format(new Date)

    def print(event: Any) {
      event match {
        case e: Error   ⇒ error(e)
        case e: Warning ⇒ warning(e)
        case e: Info    ⇒ info(e)
        case e: Debug   ⇒ debug(e)
        case e          ⇒ warning(Warning(simpleName(this), "received unexpected event of class " + e.getClass + ": " + e))
      }
    }

    def error(event: Error) = {
      val f = if (event.cause == Error.NoCause) errorFormatWithoutCause else errorFormat
      println(f.format(
        timestamp,
        event.thread.getName,
        event.logSource,
        event.message,
        stackTraceFor(event.cause)))
    }

    def warning(event: Warning) =
      println(warningFormat.format(
        timestamp,
        event.thread.getName,
        event.logSource,
        event.message))

    def info(event: Info) =
      println(infoFormat.format(
        timestamp,
        event.thread.getName,
        event.logSource,
        event.message))

    def debug(event: Debug) =
      println(debugFormat.format(
        timestamp,
        event.thread.getName,
        event.logSource,
        event.message))
  }

  /**
   * Actor-less logging implementation for synchronous logging to standard
   * output. This logger is always attached first in order to be able to log
   * failures during application start-up, even before normal logging is
   * started. Its log level can be configured by setting
   * <code>akka.stdout-loglevel</code> in <code>akka.conf</code>.
   */
  class StandardOutLogger extends MinimalActorRef with StdOutLogger {
    val path: ActorPath = new RootActorPath(LocalAddress("all-systems"), "/StandardOutLogger")
    override val toString = "StandardOutLogger"
    override def !(message: Any)(implicit sender: ActorRef = null): Unit = print(message)
  }
  val StandardOutLogger = new StandardOutLogger
  val StandardOutLoggerName = StandardOutLogger.getClass.getName

  /**
   * Actor wrapper around the standard output logger. If
   * <code>akka.event-handlers</code> is not set, it defaults to just this
   * logger.
   */
  class DefaultLogger extends Actor with StdOutLogger {
    def receive = {
      case InitializeLogger(_) ⇒ sender ! LoggerInitialized
      case event: LogEvent     ⇒ print(event)
    }
  }

  /**
   * Returns the StackTrace for the given Throwable as a String
   */
  def stackTraceFor(e: Throwable): String = e match {
    case null | Error.NoCause ⇒ ""
    case other ⇒
      val sw = new java.io.StringWriter
      val pw = new java.io.PrintWriter(sw)
      other.printStackTrace(pw)
      sw.toString
  }

}

/**
 * Logging wrapper to make nicer and optimize: provide template versions which
 * evaluate .toString only if the log level is actually enabled. Typically used
 * by obtaining an implementation from the Logging object:
 *
 * <code><pre>
 * val log = Logging(&lt;bus&gt;, &lt;source object&gt;)
 * ...
 * log.info("hello world!")
 * </pre></code>
 *
 * All log-level methods support simple interpolation templates with up to four
 * arguments placed by using <code>{}</code> within the template (first string
 * argument):
 *
 * <code><pre>
 * log.error(exception, "Exception while processing {} in state {}", msg, state)
 * </pre></code>
 */
trait LoggingAdapter {

  /*
   * implement these as precisely as needed/possible: always returning true
   * just makes the notify... methods be called every time.
   */
  def isErrorEnabled: Boolean
  def isWarningEnabled: Boolean
  def isInfoEnabled: Boolean
  def isDebugEnabled: Boolean

  /*
   * These actually implement the passing on of the messages to be logged.
   * Will not be called if is...Enabled returned false.
   */
  protected def notifyError(message: String)
  protected def notifyError(cause: Throwable, message: String)
  protected def notifyWarning(message: String)
  protected def notifyInfo(message: String)
  protected def notifyDebug(message: String)

  /*
   * The rest is just the widening of the API for the user's convenience.
   */

  def error(cause: Throwable, message: String) { if (isErrorEnabled) notifyError(cause, message) }
  def error(cause: Throwable, template: String, arg1: Any) { if (isErrorEnabled) notifyError(cause, format(template, arg1)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any) { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any) { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2, arg3)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2, arg3, arg4)) }

  def error(message: String) { if (isErrorEnabled) notifyError(message) }
  def error(template: String, arg1: Any) { if (isErrorEnabled) notifyError(format(template, arg1)) }
  def error(template: String, arg1: Any, arg2: Any) { if (isErrorEnabled) notifyError(format(template, arg1, arg2)) }
  def error(template: String, arg1: Any, arg2: Any, arg3: Any) { if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3)) }
  def error(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3, arg4)) }

  def warning(message: String) { if (isWarningEnabled) notifyWarning(message) }
  def warning(template: String, arg1: Any) { if (isWarningEnabled) notifyWarning(format(template, arg1)) }
  def warning(template: String, arg1: Any, arg2: Any) { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2)) }
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any) { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3)) }
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3, arg4)) }

  def info(message: String) { if (isInfoEnabled) notifyInfo(message) }
  def info(template: String, arg1: Any) { if (isInfoEnabled) notifyInfo(format(template, arg1)) }
  def info(template: String, arg1: Any, arg2: Any) { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2)) }
  def info(template: String, arg1: Any, arg2: Any, arg3: Any) { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3)) }
  def info(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3, arg4)) }

  def debug(message: String) { if (isDebugEnabled) notifyDebug(message) }
  def debug(template: String, arg1: Any) { if (isDebugEnabled) notifyDebug(format(template, arg1)) }
  def debug(template: String, arg1: Any, arg2: Any) { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2)) }
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any) { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3)) }
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3, arg4)) }

  def log(level: Logging.LogLevel, message: String) { if (isEnabled(level)) notifyLog(level, message) }
  def log(level: Logging.LogLevel, template: String, arg1: Any) { if (isEnabled(level)) notifyLog(level, format(template, arg1)) }
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any) { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2)) }
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any) { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2, arg3)) }
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2, arg3, arg4)) }

  final def isEnabled(level: Logging.LogLevel): Boolean = level match {
    case Logging.ErrorLevel   ⇒ isErrorEnabled
    case Logging.WarningLevel ⇒ isWarningEnabled
    case Logging.InfoLevel    ⇒ isInfoEnabled
    case Logging.DebugLevel   ⇒ isDebugEnabled
  }

  final def notifyLog(level: Logging.LogLevel, message: String): Unit = level match {
    case Logging.ErrorLevel   ⇒ if (isErrorEnabled) notifyError(message)
    case Logging.WarningLevel ⇒ if (isWarningEnabled) notifyWarning(message)
    case Logging.InfoLevel    ⇒ if (isInfoEnabled) notifyInfo(message)
    case Logging.DebugLevel   ⇒ if (isDebugEnabled) notifyDebug(message)
  }

  def format(t: String, arg: Any*) = {
    val sb = new StringBuilder
    var p = 0
    var rest = t
    while (p < arg.length) {
      val index = rest.indexOf("{}")
      sb.append(rest.substring(0, index))
      sb.append(arg(p))
      rest = rest.substring(index + 2)
      p += 1
    }
    sb.append(rest)
    sb.toString
  }
}

class BusLogging(val bus: LoggingBus, val logSource: String) extends LoggingAdapter {

  import Logging._

  def isErrorEnabled = bus.logLevel >= ErrorLevel
  def isWarningEnabled = bus.logLevel >= WarningLevel
  def isInfoEnabled = bus.logLevel >= InfoLevel
  def isDebugEnabled = bus.logLevel >= DebugLevel

  protected def notifyError(message: String) { bus.publish(Error(logSource, message)) }

  protected def notifyError(cause: Throwable, message: String) { bus.publish(Error(cause, logSource, message)) }

  protected def notifyWarning(message: String) { bus.publish(Warning(logSource, message)) }

  protected def notifyInfo(message: String) { bus.publish(Info(logSource, message)) }

  protected def notifyDebug(message: String) { bus.publish(Debug(logSource, message)) }

}
