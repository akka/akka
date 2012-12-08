/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import language.existentials

import akka.actor._
import akka.{ ConfigurationException, AkkaException }
import akka.actor.ActorSystem.Settings
import akka.util.{ Timeout, ReentrantGuard }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeoutException
import scala.annotation.implicitNotFound
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.control.NoStackTrace

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

  private val guard = new ReentrantGuard //Switch to ReentrantReadWrite
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
      StandardOutLogger.print(Error(new EventHandlerException, simpleName(this), this.getClass, "unknown akka.stdout-loglevel " + config.StdoutLogLevel))
      ErrorLevel
    }
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(StandardOutLogger, classFor(l)))
    guard.withGuard {
      loggers = Seq(StandardOutLogger)
      _logLevel = level
    }
    publish(Debug(simpleName(this), this.getClass, "StandardOutLogger started"))
  }

  /**
   * Internal Akka use only
   */
  private[akka] def startDefaultLoggers(system: ActorSystemImpl) {
    val logName = simpleName(this) + "(" + system + ")"
    val level = levelFor(system.settings.LogLevel) getOrElse {
      StandardOutLogger.print(Error(new EventHandlerException, logName, this.getClass, "unknown akka.stdout-loglevel " + system.settings.LogLevel))
      ErrorLevel
    }
    try {
      val defaultLoggers = system.settings.EventHandlers match {
        case Nil     ⇒ "akka.event.Logging$DefaultLogger" :: Nil
        case loggers ⇒ loggers
      }
      val myloggers =
        for {
          loggerName ← defaultLoggers
          if loggerName != StandardOutLogger.getClass.getName
        } yield {
          system.dynamicAccess.getClassFor[Actor](loggerName).map({
            case actorClass ⇒ addLogger(system, actorClass, level, logName)
          }).recover({
            case e ⇒ throw new ConfigurationException(
              "Event Handler specified in config can't be loaded [" + loggerName +
                "] due to [" + e.toString + "]", e)
          }).get
        }
      guard.withGuard {
        loggers = myloggers
        _logLevel = level
      }
      try {
        if (system.settings.DebugUnhandledMessage)
          subscribe(system.systemActorOf(Props(new Actor {
            def receive = {
              case UnhandledMessage(msg, sender, rcp) ⇒
                publish(Debug(rcp.path.toString, rcp.getClass, "unhandled message from " + sender + ": " + msg))
            }
          }), "UnhandledMessageForwarder"), classOf[UnhandledMessage])
      } catch {
        case _: InvalidActorNameException ⇒ // ignore if it is already running
      }
      publish(Debug(logName, this.getClass, "Default Loggers started"))
      if (!(defaultLoggers contains StandardOutLogger.getClass.getName)) {
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
      publish(Debug(simpleName(this), this.getClass, "shutting down: StandardOutLogger started"))
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
    publish(Debug(simpleName(this), this.getClass, "all default loggers stopped"))
  }

  /**
   * INTERNAL API
   */
  private def addLogger(system: ActorSystemImpl, clazz: Class[_ <: Actor], level: LogLevel, logName: String): ActorRef = {
    val name = "log" + Extension(system).id() + "-" + simpleName(clazz)
    val actor = system.systemActorOf(Props(clazz), name)
    implicit def timeout = system.settings.EventHandlerStartTimeout
    import akka.pattern.ask
    val response = try Await.result(actor ? InitializeLogger(this), timeout.duration) catch {
      case _: TimeoutException ⇒
        publish(Warning(logName, this.getClass, "Logger " + name + " did not respond within " + timeout + " to InitializeLogger(bus)"))
        "[TIMEOUT]"
    }
    if (response != LoggerInitialized)
      throw new LoggerInitializationException("Logger " + name + " did not respond with LoggerInitialized, sent instead " + response)
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(actor, classFor(l)))
    publish(Debug(logName, this.getClass, "logger " + name + " started"))
    actor
  }

}

/**
 * This trait defines the interface to be provided by a “log source formatting
 * rule” as used by [[akka.event.Logging]]’s `apply`/`create` method.
 *
 * See the companion object for default implementations.
 *
 * Example:
 * {{{
 * trait MyType { // as an example
 *   def name: String
 * }
 *
 * implicit val myLogSourceType: LogSource[MyType] = new LogSource {
 *   def genString(a: MyType) = a.name
 * }
 *
 * class MyClass extends MyType {
 *   val log = Logging(eventStream, this) // will use "hallo" as logSource
 *   def name = "hallo"
 * }
 * }}}
 *
 * The second variant is used for including the actor system’s address:
 * {{{
 * trait MyType { // as an example
 *   def name: String
 * }
 *
 * implicit val myLogSourceType: LogSource[MyType] = new LogSource {
 *   def genString(a: MyType) = a.name
 *   def genString(a: MyType, s: ActorSystem) = a.name + "," + s
 * }
 *
 * class MyClass extends MyType {
 *   val sys = ActorSyste("sys")
 *   val log = Logging(sys, this) // will use "hallo,akka://sys" as logSource
 *   def name = "hallo"
 * }
 * }}}
 *
 * The default implementation of the second variant will just call the first.
 */
@implicitNotFound("Cannot find LogSource for ${T} please see ScalaDoc for LogSource for how to obtain or construct one.") trait LogSource[-T] {
  def genString(t: T): String
  def genString(t: T, system: ActorSystem): String = genString(t)
  def getClazz(t: T): Class[_] = t.getClass
}

/**
 * This is a “marker” class which is inserted as originator class into
 * [[akka.event.Logging.LogEvent]] when the string representation was supplied
 * directly.
 */
class DummyClassForStringSources

/**
 * This object holds predefined formatting rules for log sources.
 *
 * In case an [[akka.actor.ActorSystem]] is provided, the following apply:
 * <ul>
 * <li>[[akka.actor.Actor]] and [[akka.actor.ActorRef]] will be represented by their absolute physical path</li>
 * <li>providing a `String` as source will append "(<system address>)" and use the result</li>
 * <li>providing a `Class` will extract its simple name, append "(<system address>)" and use the result</li>
 * <li>anything else gives compile error unless implicit [[akka.event.LogSource]] is in scope for it</li>
 * </ul>
 *
 * In case a [[akka.event.LoggingBus]] is provided, the following apply:
 * <ul>
 * <li>[[akka.actor.Actor]] and [[akka.actor.ActorRef]] will be represented by their absolute physical path</li>
 * <li>providing a `String` as source will be used as is</li>
 * <li>providing a `Class` will extract its simple name</li>
 * <li>anything else gives compile error unless implicit [[akka.event.LogSource]] is in scope for it</li>
 * </ul>
 */
object LogSource {
  implicit val fromString: LogSource[String] = new LogSource[String] {
    def genString(s: String) = s
    override def genString(s: String, system: ActorSystem) = s + "(" + system + ")"
    override def getClazz(s: String) = classOf[DummyClassForStringSources]
  }

  implicit val fromActor: LogSource[Actor] = new LogSource[Actor] {
    def genString(a: Actor) = a.self.path.toString
  }

  implicit val fromActorRef: LogSource[ActorRef] = new LogSource[ActorRef] {
    def genString(a: ActorRef) = a.path.toString
  }

  // this one unfortunately does not work as implicit, because existential types have some weird behavior
  val fromClass: LogSource[Class[_]] = new LogSource[Class[_]] {
    def genString(c: Class[_]): String = Logging.simpleName(c)
    override def genString(c: Class[_], system: ActorSystem): String = genString(c) + "(" + system + ")"
    override def getClazz(c: Class[_]): Class[_] = c
  }
  implicit def fromAnyClass[T]: LogSource[Class[T]] = fromClass.asInstanceOf[LogSource[Class[T]]]

  /**
   * Convenience converter access: given an implicit `LogSource`, generate the
   * string representation and originating class.
   */
  def apply[T: LogSource](o: T): (String, Class[_]) = {
    val ls = implicitly[LogSource[T]]
    (ls.genString(o), ls.getClazz(o))
  }

  /**
   * Convenience converter access: given an implicit `LogSource` and
   * [[akka.actor.ActorSystem]], generate the string representation and
   * originating class.
   */
  def apply[T: LogSource](o: T, system: ActorSystem): (String, Class[_]) = {
    val ls = implicitly[LogSource[T]]
    (ls.genString(o, system), ls.getClazz(o))
  }

  /**
   * construct string representation for any object according to
   * rules above with fallback to its `Class`’s simple name.
   */
  def fromAnyRef(o: AnyRef): (String, Class[_]) =
    o match {
      case c: Class[_] ⇒ apply(c)
      case a: Actor    ⇒ apply(a)
      case a: ActorRef ⇒ apply(a)
      case s: String   ⇒ apply(s)
      case x           ⇒ (Logging.simpleName(x), x.getClass)
    }

  /**
   * construct string representation for any object according to
   * rules above (including the actor system’s address) with fallback to its
   * `Class`’s simple name.
   */
  def fromAnyRef(o: AnyRef, system: ActorSystem): (String, Class[_]) =
    o match {
      case c: Class[_] ⇒ apply(c)
      case a: Actor    ⇒ apply(a)
      case a: ActorRef ⇒ apply(a)
      case s: String   ⇒ apply(s)
      case x           ⇒ (Logging.simpleName(x) + "(" + system + ")", x.getClass)
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
 * The source object is used in two fashions: its `Class[_]` will be part of
 * all log events produced by this logger, plus a string representation is
 * generated which may contain per-instance information, see `apply` or `create`
 * below.
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

  /**
   * Returns a 'safe' getSimpleName for the provided object's Class
   * @param obj
   * @return the simple name of the given object's Class
   */
  def simpleName(obj: AnyRef): String = simpleName(obj.getClass)

  /**
   * Returns a 'safe' getSimpleName for the provided Class
   * @param obj
   * @return the simple name of the given Class
   */
  def simpleName(clazz: Class[_]): String = {
    val n = clazz.getName
    val i = n.lastIndexOf('.')
    n.substring(i + 1)
  }

  /**
   * INTERNAL API
   */
  private[akka] object Extension extends ExtensionKey[LogExt]

  /**
   * INTERNAL API
   */
  private[akka] class LogExt(system: ExtendedActorSystem) extends Extension {
    private val loggerId = new AtomicInteger
    def id() = loggerId.incrementAndGet()
  }

  /**
   * Marker trait for annotating LogLevel, which must be Int after erasure.
   */
  case class LogLevel(asInt: Int) extends AnyVal {
    @inline final def >=(other: LogLevel): Boolean = asInt >= other.asInt
    @inline final def <=(other: LogLevel): Boolean = asInt <= other.asInt
    @inline final def >(other: LogLevel): Boolean = asInt > other.asInt
    @inline final def <(other: LogLevel): Boolean = asInt < other.asInt
  }

  /**
   * Log level in numeric form, used when deciding whether a certain log
   * statement should generate a log event. Predefined levels are ErrorLevel (1)
   * to DebugLevel (4). In case you want to add more levels, loggers need to
   * be subscribed to their event bus channels manually.
   */
  final val ErrorLevel = LogLevel(1)
  final val WarningLevel = LogLevel(2)
  final val InfoLevel = LogLevel(3)
  final val DebugLevel = LogLevel(4)

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
  val AllLogLevels: immutable.Seq[LogLevel] = Vector(ErrorLevel, WarningLevel, InfoLevel, DebugLevel)

  /**
   * Obtain LoggingAdapter for the given actor system and source object. This
   * will use the system’s event stream and include the system’s address in the
   * log source string.
   *
   * <b>Do not use this if you want to supply a log category string (like
   * “com.example.app.whatever”) unaltered,</b> supply `system.eventStream` in this
   * case or use
   *
   * {{{
   * Logging(system, this.getClass)
   * }}}
   *
   * The source is used to identify the source of this logging channel and
   * must have a corresponding implicit LogSource[T] instance in scope; by
   * default these are provided for Class[_], Actor, ActorRef and String types.
   * See the companion object of [[akka.event.LogSource]] for details.
   *
   * You can add your own rules quite easily, see [[akka.event.LogSource]].
   */
  def apply[T: LogSource](system: ActorSystem, logSource: T): LoggingAdapter = {
    val (str, clazz) = LogSource(logSource, system)
    new BusLogging(system.eventStream, str, clazz)
  }

  /**
   * Obtain LoggingAdapter for the given logging bus and source object.
   *
   * The source is used to identify the source of this logging channel and
   * must have a corresponding implicit LogSource[T] instance in scope; by
   * default these are provided for Class[_], Actor, ActorRef and String types.
   * See the companion object of [[akka.event.LogSource]] for details.
   *
   * You can add your own rules quite easily, see [[akka.event.LogSource]].
   */
  def apply[T: LogSource](bus: LoggingBus, logSource: T): LoggingAdapter = {
    val (str, clazz) = LogSource(logSource)
    new BusLogging(bus, str, clazz)
  }

  /**
   * Obtain LoggingAdapter for the given actor system and source object. This
   * will use the system’s event stream and include the system’s address in the
   * log source string.
   *
   * <b>Do not use this if you want to supply a log category string (like
   * “com.example.app.whatever”) unaltered,</b> supply `system.eventStream` in this
   * case or use
   *
   * {{{
   * Logging.getLogger(system, this.getClass());
   * }}}
   *
   * The source is used to identify the source of this logging channel and
   * must have a corresponding implicit LogSource[T] instance in scope; by
   * default these are provided for Class[_], Actor, ActorRef and String types.
   * See the companion object of [[akka.event.LogSource]] for details.
   */
  def getLogger(system: ActorSystem, logSource: AnyRef): LoggingAdapter = {
    val (str, clazz) = LogSource.fromAnyRef(logSource, system)
    new BusLogging(system.eventStream, str, clazz)
  }

  /**
   * Obtain LoggingAdapter for the given logging bus and source object.
   *
   * The source is used to identify the source of this logging channel and
   * must have a corresponding implicit LogSource[T] instance in scope; by
   * default these are provided for Class[_], Actor, ActorRef and String types.
   * See the companion object of [[akka.event.LogSource]] for details.
   */
  def getLogger(bus: LoggingBus, logSource: AnyRef): LoggingAdapter = {
    val (str, clazz) = LogSource.fromAnyRef(logSource)
    new BusLogging(bus, str, clazz)
  }

  /**
   * Artificial exception injected into Error events if no Throwable is
   * supplied; used for getting a stack dump of error locations.
   */
  class EventHandlerException extends AkkaException("")

  /**
   * Exception that wraps a LogEvent.
   */
  class LogEventException(val event: LogEvent, cause: Throwable) extends NoStackTrace {
    override def getMessage: String = event.toString
    override def getCause: Throwable = cause
  }

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

    /**
     * The source of this event
     */
    def logSource: String

    /**
     * The class of the source of this event
     */
    def logClass: Class[_]

    /**
     * The message, may be any object or null.
     */
    def message: Any
  }

  /**
   * For ERROR Logging
   */
  case class Error(cause: Throwable, logSource: String, logClass: Class[_], message: Any = "") extends LogEvent {
    def this(logSource: String, logClass: Class[_], message: Any) = this(Error.NoCause, logSource, logClass, message)

    override def level = ErrorLevel
  }

  object Error {
    def apply(logSource: String, logClass: Class[_], message: Any) = new Error(NoCause, logSource, logClass, message)

    /** Null Object used for errors without cause Throwable */
    object NoCause extends NoStackTrace
  }
  def noCause = Error.NoCause

  /**
   * For WARNING Logging
   */
  case class Warning(logSource: String, logClass: Class[_], message: Any = "") extends LogEvent {
    override def level = WarningLevel
  }

  /**
   * For INFO Logging
   */
  case class Info(logSource: String, logClass: Class[_], message: Any = "") extends LogEvent {
    override def level = InfoLevel
  }

  /**
   * For DEBUG Logging
   */
  case class Debug(logSource: String, logClass: Class[_], message: Any = "") extends LogEvent {
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
  abstract class LoggerInitialized
  case object LoggerInitialized extends LoggerInitialized {
    /**
     * Java API: get the singleton instance
     */
    def getInstance = this
  }

  /**
   * Java API to create a LoggerInitialized message.
   */
  // weird return type due to binary compatibility
  def loggerInitialized(): LoggerInitialized.type = LoggerInitialized

  /**
   * LoggerInitializationException is thrown to indicate that there was a problem initializing a logger
   * @param msg
   */
  class LoggerInitializationException(msg: String) extends AkkaException(msg)

  trait StdOutLogger {
    import java.text.SimpleDateFormat
    import java.util.Date

    private val dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.SSS")
    private val errorFormat = "[ERROR] [%s] [%s] [%s] %s%s".intern
    private val errorFormatWithoutCause = "[ERROR] [%s] [%s] [%s] %s".intern
    private val warningFormat = "[WARN] [%s] [%s] [%s] %s".intern
    private val infoFormat = "[INFO] [%s] [%s] [%s] %s".intern
    private val debugFormat = "[DEBUG] [%s] [%s] [%s] %s".intern

    def timestamp(): String = synchronized { dateFormat.format(new Date) } // SDF isn't threadsafe

    def print(event: Any): Unit = event match {
      case e: Error   ⇒ error(e)
      case e: Warning ⇒ warning(e)
      case e: Info    ⇒ info(e)
      case e: Debug   ⇒ debug(e)
      case e          ⇒ warning(Warning(simpleName(this), this.getClass, "received unexpected event of class " + e.getClass + ": " + e))
    }

    def error(event: Error): Unit = {
      val f = if (event.cause == Error.NoCause) errorFormatWithoutCause else errorFormat
      println(f.format(
        timestamp,
        event.thread.getName,
        event.logSource,
        event.message,
        stackTraceFor(event.cause)))
    }

    def warning(event: Warning): Unit =
      println(warningFormat.format(
        timestamp,
        event.thread.getName,
        event.logSource,
        event.message))

    def info(event: Info): Unit =
      println(infoFormat.format(
        timestamp,
        event.thread.getName,
        event.logSource,
        event.message))

    def debug(event: Debug): Unit =
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
    val path: ActorPath = new RootActorPath(Address("akka", "all-systems"), "/StandardOutLogger")
    def provider: ActorRefProvider = throw new UnsupportedOperationException("StandardOutLogger does not provide")
    override val toString = "StandardOutLogger"
    override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = print(message)
  }

  val StandardOutLogger = new StandardOutLogger

  /**
   * Actor wrapper around the standard output logger. If
   * <code>akka.event-handlers</code> is not set, it defaults to just this
   * logger.
   */
  class DefaultLogger extends Actor with StdOutLogger {
    override def receive: Receive = {
      case InitializeLogger(_) ⇒ sender ! LoggerInitialized
      case event: LogEvent     ⇒ print(event)
    }
  }

  /**
   * Returns the StackTrace for the given Throwable as a String
   */
  def stackTraceFor(e: Throwable): String = e match {
    case null | Error.NoCause ⇒ ""
    case _: NoStackTrace      ⇒ " (" + e.getClass.getName + ")"
    case other ⇒
      val sw = new java.io.StringWriter
      val pw = new java.io.PrintWriter(sw)
      pw.append('\n')
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
  protected def notifyError(message: String): Unit
  protected def notifyError(cause: Throwable, message: String): Unit
  protected def notifyWarning(message: String): Unit
  protected def notifyInfo(message: String): Unit
  protected def notifyDebug(message: String): Unit

  /*
   * The rest is just the widening of the API for the user's convenience.
   */

  def error(cause: Throwable, message: String): Unit = { if (isErrorEnabled) notifyError(cause, message) }
  def error(cause: Throwable, template: String, arg1: Any): Unit = { if (isErrorEnabled) notifyError(cause, format1(template, arg1)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any): Unit = { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2, arg3)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2, arg3, arg4)) }

  def error(message: String): Unit = { if (isErrorEnabled) notifyError(message) }
  def error(template: String, arg1: Any): Unit = { if (isErrorEnabled) notifyError(format1(template, arg1)) }
  def error(template: String, arg1: Any, arg2: Any): Unit = { if (isErrorEnabled) notifyError(format(template, arg1, arg2)) }
  def error(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3)) }
  def error(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3, arg4)) }

  def warning(message: String): Unit = { if (isWarningEnabled) notifyWarning(message) }
  def warning(template: String, arg1: Any): Unit = { if (isWarningEnabled) notifyWarning(format1(template, arg1)) }
  def warning(template: String, arg1: Any, arg2: Any): Unit = { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2)) }
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3)) }
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3, arg4)) }

  def info(message: String) { if (isInfoEnabled) notifyInfo(message) }
  def info(template: String, arg1: Any): Unit = { if (isInfoEnabled) notifyInfo(format1(template, arg1)) }
  def info(template: String, arg1: Any, arg2: Any): Unit = { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2)) }
  def info(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3)) }
  def info(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3, arg4)) }

  def debug(message: String) { if (isDebugEnabled) notifyDebug(message) }
  def debug(template: String, arg1: Any): Unit = { if (isDebugEnabled) notifyDebug(format1(template, arg1)) }
  def debug(template: String, arg1: Any, arg2: Any): Unit = { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2)) }
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3)) }
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3, arg4)) }

  def log(level: Logging.LogLevel, message: String) { if (isEnabled(level)) notifyLog(level, message) }
  def log(level: Logging.LogLevel, template: String, arg1: Any): Unit = { if (isEnabled(level)) notifyLog(level, format1(template, arg1)) }
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any): Unit = { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2)) }
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2, arg3)) }
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2, arg3, arg4)) }

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

  private def format1(t: String, arg: Any): String = arg match {
    case a: Array[_] if !a.getClass.getComponentType.isPrimitive ⇒ format(t, a: _*)
    case a: Array[_] ⇒ format(t, (a map (_.asInstanceOf[AnyRef]): _*))
    case x ⇒ format(t, x)
  }

  def format(t: String, arg: Any*): String = {
    val sb = new StringBuilder(64)
    var p = 0
    var rest = t
    while (p < arg.length) {
      val index = rest.indexOf("{}")
      if (index == -1) {
        sb.append(rest).append(" WARNING arguments left: ").append(arg.length - p)
        rest = ""
        p = arg.length
      } else {
        sb.append(rest.substring(0, index)).append(arg(p))
        rest = rest.substring(index + 2)
        p += 1
      }
    }
    sb.append(rest).toString
  }
}
//FIXME DOCUMENT
class BusLogging(val bus: LoggingBus, val logSource: String, val logClass: Class[_]) extends LoggingAdapter {

  import Logging._

  def isErrorEnabled = bus.logLevel >= ErrorLevel
  def isWarningEnabled = bus.logLevel >= WarningLevel
  def isInfoEnabled = bus.logLevel >= InfoLevel
  def isDebugEnabled = bus.logLevel >= DebugLevel

  protected def notifyError(message: String): Unit = bus.publish(Error(logSource, logClass, message))
  protected def notifyError(cause: Throwable, message: String): Unit = bus.publish(Error(cause, logSource, logClass, message))
  protected def notifyWarning(message: String): Unit = bus.publish(Warning(logSource, logClass, message))
  protected def notifyInfo(message: String): Unit = bus.publish(Info(logSource, logClass, message))
  protected def notifyDebug(message: String): Unit = bus.publish(Debug(logSource, logClass, message))
}

/**
 * NoLogging is a LoggingAdapter that does absolutely nothing – no logging at all.
 */
object NoLogging extends LoggingAdapter {

  /**
   * Java API to return the reference to NoLogging
   * @return The NoLogging instance
   */
  def getInstance = this

  final override def isErrorEnabled = false
  final override def isWarningEnabled = false
  final override def isInfoEnabled = false
  final override def isDebugEnabled = false

  final protected override def notifyError(message: String): Unit = ()
  final protected override def notifyError(cause: Throwable, message: String): Unit = ()
  final protected override def notifyWarning(message: String): Unit = ()
  final protected override def notifyInfo(message: String): Unit = ()
  final protected override def notifyDebug(message: String): Unit = ()
}
