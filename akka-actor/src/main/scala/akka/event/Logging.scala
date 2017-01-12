/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.event

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging

import akka.actor.ActorSystem.Settings
import akka.actor._
import akka.dispatch.RequiresMessageQueue
import akka.event.Logging.{ Extension ⇒ _, _ }
import akka.util.{ OptionVal, ReentrantGuard }
import akka.util.Helpers.toRootLowerCase
import akka.{ AkkaException, ConfigurationException }

import scala.annotation.implicitNotFound
import scala.collection.immutable
import scala.concurrent.Await
import scala.language.existentials
import scala.util.control.{ NoStackTrace, NonFatal }

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
  @volatile private var _logLevel: LogLevel = _

  /**
   * Query currently set log level. See object Logging for more information.
   */
  def logLevel = _logLevel

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
    val logLvl = _logLevel // saves (2 * AllLogLevel.size - 1) volatile reads (because of the loops below)
    for {
      l ← AllLogLevels
      // subscribe if previously ignored and now requested
      if l > logLvl && l <= level
      log ← loggers
    } subscribe(log, classFor(l))
    for {
      l ← AllLogLevels
      // unsubscribe if previously registered and now ignored
      if l <= logLvl && l > level
      log ← loggers
    } unsubscribe(log, classFor(l))
    _logLevel = level
  }

  private def setUpStdoutLogger(config: Settings) {
    val level = levelFor(config.StdoutLogLevel) getOrElse {
      // only log initialization errors directly with StandardOutLogger.print
      StandardOutLogger.print(Error(new LoggerException, simpleName(this), this.getClass, "unknown akka.stdout-loglevel " + config.StdoutLogLevel))
      ErrorLevel
    }
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(StandardOutLogger, classFor(l)))
    guard.withGuard {
      loggers :+= StandardOutLogger
      _logLevel = level
    }
  }

  /**
   * Internal Akka use only
   */
  private[akka] def startStdoutLogger(config: Settings) {
    setUpStdoutLogger(config)
    publish(Debug(simpleName(this), this.getClass, "StandardOutLogger started"))
  }

  /**
   * Internal Akka use only
   */
  private[akka] def startDefaultLoggers(system: ActorSystemImpl) {
    val logName = simpleName(this) + "(" + system + ")"
    val level = levelFor(system.settings.LogLevel) getOrElse {
      // only log initialization errors directly with StandardOutLogger.print
      StandardOutLogger.print(Error(new LoggerException, logName, this.getClass, "unknown akka.loglevel " + system.settings.LogLevel))
      ErrorLevel
    }
    try {
      val defaultLoggers = system.settings.Loggers match {
        case Nil     ⇒ classOf[DefaultLogger].getName :: Nil
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
              "Logger specified in config can't be loaded [" + loggerName +
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
        System.err.println("error while starting up loggers")
        e.printStackTrace()
        throw new ConfigurationException("Could not start logger due to [" + e.toString + "]")
    }
  }

  /**
   * Internal Akka use only
   */
  private[akka] def stopDefaultLoggers(system: ActorSystem) {
    val level = _logLevel // volatile access before reading loggers
    if (!(loggers contains StandardOutLogger)) {
      setUpStdoutLogger(system.settings)
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
    val actor = system.systemActorOf(Props(clazz).withDispatcher(system.settings.LoggersDispatcher), name)
    implicit def timeout = system.settings.LoggerStartTimeout
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
 * implicit val myLogSourceType: LogSource[MyType] = new LogSource[MyType] {
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
 * implicit val myLogSourceType: LogSource[MyType] = new LogSource[MyType] {
 *   def genString(a: MyType) = a.name
 *   def genString(a: MyType, s: ActorSystem) = a.name + "," + s
 * }
 *
 * class MyClass extends MyType {
 *   val sys = ActorSystem("sys")
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
 * <li>providing a `String` as source will append "(&lt;system address&gt;)" and use the result</li>
 * <li>providing a `Class` will extract its simple name, append "(&lt;system address&gt;)" and use the result</li>
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
    def genString(a: Actor) = fromActorRef.genString(a.self)
    override def genString(a: Actor, system: ActorSystem) = fromActorRef.genString(a.self, system)
  }

  implicit val fromActorRef: LogSource[ActorRef] = new LogSource[ActorRef] {
    def genString(a: ActorRef) = a.path.toString
    override def genString(a: ActorRef, system: ActorSystem) = try {
      a.path.toStringWithAddress(system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress)
    } catch {
      // it can fail if the ActorSystem (remoting) is not completely started yet
      case NonFatal(_) ⇒ a.path.toString
    }
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
 * Logging is configured by setting (some of) the following:
 *
 * <pre><code>
 * akka {
 *   loggers = ["akka.slf4j.Slf4jLogger"] # for example
 *   loglevel = "INFO"        # used when normal logging ("loggers") has been started
 *   stdout-loglevel = "WARN" # used during application start-up until normal logging is available
 * }
 * </code></pre>
 */
object Logging {

  /**
   * Returns a 'safe' getSimpleName for the provided object's Class
   * @return the simple name of the given object's Class
   */
  def simpleName(obj: AnyRef): String = simpleName(obj.getClass)

  /**
   * Returns a 'safe' getSimpleName for the provided Class
   * @return the simple name of the given Class
   */
  def simpleName(clazz: Class[_]): String = {
    val n = clazz.getName
    val i = n.lastIndexOf('.')
    n.substring(i + 1)
  }

  /**
   * Class name representation of a message.
   * `ActorSelectionMessage` representation includes class name of
   * wrapped message.
   */
  def messageClassName(message: Any): String = message match {
    case null                           ⇒ "null"
    case ActorSelectionMessage(m, _, _) ⇒ s"ActorSelectionMessage(${m.getClass.getName})"
    case m                              ⇒ m.getClass.getName
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
  final case class LogLevel(asInt: Int) extends AnyVal {
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
   * Internal Akka use only
   *
   * Don't include the OffLevel in the AllLogLevels since we should never subscribe
   * to some kind of OffEvent.
   */
  private final val OffLevel = LogLevel(Int.MinValue)

  /**
   * Returns the LogLevel associated with the given string,
   * valid inputs are upper or lowercase (not mixed) versions of:
   * "error", "warning", "info" and "debug"
   */
  def levelFor(s: String): Option[LogLevel] = toRootLowerCase(s) match {
    case "off"     ⇒ Some(OffLevel)
    case "error"   ⇒ Some(ErrorLevel)
    case "warning" ⇒ Some(WarningLevel)
    case "info"    ⇒ Some(InfoLevel)
    case "debug"   ⇒ Some(DebugLevel)
    case unknown   ⇒ None
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
    new BusLogging(system.eventStream, str, clazz, system.asInstanceOf[ExtendedActorSystem].logFilter)
  }
  /**
   * Obtain LoggingAdapter with additional "marker" support (which some logging frameworks are able to utilise)
   * for the given actor system and source object. This will use the system’s event stream and include the system’s
   * address in the log source string.
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
  def withMarker[T: LogSource](system: ActorSystem, logSource: T): MarkerLoggingAdapter = {
    val (str, clazz) = LogSource(logSource, system)
    new MarkerLoggingAdapter(system.eventStream, str, clazz, system.asInstanceOf[ExtendedActorSystem].logFilter)
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
   *
   * Note that this `LoggingAdapter` will use the [[akka.event.DefaultLoggingFilter]],
   * and not the [[akka.event.LoggingFilter]] configured for the system
   * (if different from `DefaultLoggingFilter`).
   */
  def apply[T: LogSource](bus: LoggingBus, logSource: T): LoggingAdapter = {
    val (str, clazz) = LogSource(logSource)
    new BusLogging(bus, str, clazz)
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
   *
   * Note that this `LoggingAdapter` will use the [[akka.event.DefaultLoggingFilter]],
   * and not the [[akka.event.LoggingFilter]] configured for the system
   * (if different from `DefaultLoggingFilter`).
   */
  def withMarker[T: LogSource](bus: LoggingBus, logSource: T): MarkerLoggingAdapter = {
    val (str, clazz) = LogSource(logSource)
    new MarkerLoggingAdapter(bus, str, clazz)
  }

  /**
   * Obtain LoggingAdapter with MDC support for the given actor.
   * Don't use it outside its specific Actor as it isn't thread safe
   */
  def apply(logSource: Actor): DiagnosticLoggingAdapter = {
    val (str, clazz) = LogSource(logSource)
    val system = logSource.context.system.asInstanceOf[ExtendedActorSystem]
    new BusLogging(system.eventStream, str, clazz, system.logFilter) with DiagnosticLoggingAdapter
  }
  /**
   * Obtain LoggingAdapter with marker and MDC support for the given actor.
   * Don't use it outside its specific Actor as it isn't thread safe
   */
  def withMarker(logSource: Actor): DiagnosticMarkerBusLoggingAdapter = {
    val (str, clazz) = LogSource(logSource)
    val system = logSource.context.system.asInstanceOf[ExtendedActorSystem]
    new DiagnosticMarkerBusLoggingAdapter(system.eventStream, str, clazz, system.logFilter)
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
    new BusLogging(system.eventStream, str, clazz, system.asInstanceOf[ExtendedActorSystem].logFilter)
  }

  /**
   * Obtain LoggingAdapter for the given logging bus and source object.
   *
   * The source is used to identify the source of this logging channel and
   * must have a corresponding implicit LogSource[T] instance in scope; by
   * default these are provided for Class[_], Actor, ActorRef and String types.
   * See the companion object of [[akka.event.LogSource]] for details.
   *
   * Note that this `LoggingAdapter` will use the [[akka.event.DefaultLoggingFilter]],
   * and not the [[akka.event.LoggingFilter]] configured for the system
   * (if different from `DefaultLoggingFilter`).
   */
  def getLogger(bus: LoggingBus, logSource: AnyRef): LoggingAdapter = {
    val (str, clazz) = LogSource.fromAnyRef(logSource)
    new BusLogging(bus, str, clazz)
  }

  /**
   * Obtain LoggingAdapter with MDC support for the given actor.
   * Don't use it outside its specific Actor as it isn't thread safe
   */
  def getLogger(logSource: Actor): DiagnosticLoggingAdapter = apply(logSource)

  /**
   * Obtain LoggingAdapter with MDC support for the given actor.
   * Don't use it outside its specific Actor as it isn't thread safe
   */
  def getLogger(logSource: UntypedActor): DiagnosticLoggingAdapter = {
    val (str, clazz) = LogSource.fromAnyRef(logSource)
    val system = logSource.getContext().system.asInstanceOf[ExtendedActorSystem]
    new BusLogging(system.eventStream, str, clazz, system.logFilter) with DiagnosticLoggingAdapter
  }

  /**
   * Artificial exception injected into Error events if no Throwable is
   * supplied; used for getting a stack dump of error locations.
   */
  class LoggerException extends AkkaException("")

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
  sealed trait LogEvent extends NoSerializationVerificationNeeded {
    /**
     * The thread that created this log event
     */
    @transient
    val thread: Thread = Thread.currentThread

    /**
     * When this LogEvent was created according to System.currentTimeMillis
     */
    val timestamp: Long = System.currentTimeMillis

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

    /**
     * Extra values for adding to MDC
     */
    def mdc: MDC = emptyMDC

    /**
     * Java API: Retrieve the contents of the MDC.
     */
    def getMDC: java.util.Map[String, Any] = {
      import scala.collection.JavaConverters._
      mdc.asJava
    }
  }

  object LogEvent {
    def apply(level: LogLevel, logSource: String, logClass: Class[_], message: Any): LogEvent = level match {
      case ErrorLevel   ⇒ Error(logSource, logClass, message)
      case WarningLevel ⇒ Warning(logSource, logClass, message)
      case InfoLevel    ⇒ Info(logSource, logClass, message)
      case DebugLevel   ⇒ Debug(logSource, logClass, message)
    }

    def apply(level: LogLevel, logSource: String, logClass: Class[_], message: Any, mdc: MDC): LogEvent = level match {
      case ErrorLevel   ⇒ Error(logSource, logClass, message, mdc)
      case WarningLevel ⇒ Warning(logSource, logClass, message, mdc)
      case InfoLevel    ⇒ Info(logSource, logClass, message, mdc)
      case DebugLevel   ⇒ Debug(logSource, logClass, message, mdc)
    }

    def apply(level: LogLevel, logSource: String, logClass: Class[_], message: Any, mdc: MDC, marker: LogMarker): LogEvent = level match {
      case ErrorLevel   ⇒ Error(logSource, logClass, message, mdc, marker)
      case WarningLevel ⇒ Warning(logSource, logClass, message, mdc, marker)
      case InfoLevel    ⇒ Info(logSource, logClass, message, mdc, marker)
      case DebugLevel   ⇒ Debug(logSource, logClass, message, mdc, marker)
    }

  }

  /**
   * For ERROR Logging
   */
  case class Error(cause: Throwable, logSource: String, logClass: Class[_], message: Any = "") extends LogEvent {
    def this(logSource: String, logClass: Class[_], message: Any) = this(Error.NoCause, logSource, logClass, message)
    override def level = ErrorLevel
  }
  class Error2(cause: Throwable, logSource: String, logClass: Class[_], message: Any = "", override val mdc: MDC) extends Error(cause, logSource, logClass, message) {
    def this(logSource: String, logClass: Class[_], message: Any, mdc: MDC) = this(Error.NoCause, logSource, logClass, message, mdc)
  }
  class Error3(cause: Throwable, logSource: String, logClass: Class[_], message: Any, override val mdc: MDC, override val marker: LogMarker)
    extends Error2(cause, logSource, logClass, message, mdc) with LogEventWithMarker {
    def this(logSource: String, logClass: Class[_], message: Any, mdc: MDC, marker: LogMarker) = this(Error.NoCause, logSource, logClass, message, mdc, marker)
  }

  object Error {
    def apply(logSource: String, logClass: Class[_], message: Any) =
      new Error(NoCause, logSource, logClass, message)
    def apply(logSource: String, logClass: Class[_], message: Any, marker: LogMarker) =
      new Error3(NoCause, logSource, logClass, message, Map.empty, marker)

    def apply(cause: Throwable, logSource: String, logClass: Class[_], message: Any, mdc: MDC) =
      new Error2(cause, logSource, logClass, message, mdc)
    def apply(cause: Throwable, logSource: String, logClass: Class[_], message: Any, mdc: MDC, marker: LogMarker) =
      new Error3(cause, logSource, logClass, message, mdc, marker)

    def apply(logSource: String, logClass: Class[_], message: Any, mdc: MDC) =
      new Error2(NoCause, logSource, logClass, message, mdc)
    def apply(logSource: String, logClass: Class[_], message: Any, mdc: MDC, marker: LogMarker) =
      new Error3(NoCause, logSource, logClass, message, mdc, marker)

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
  class Warning2(logSource: String, logClass: Class[_], message: Any, override val mdc: MDC) extends Warning(logSource, logClass, message)
  class Warning3(logSource: String, logClass: Class[_], message: Any, override val mdc: MDC, override val marker: LogMarker)
    extends Warning2(logSource, logClass, message, mdc) with LogEventWithMarker
  object Warning {
    def apply(logSource: String, logClass: Class[_], message: Any, mdc: MDC) = new Warning2(logSource, logClass, message, mdc)
    def apply(logSource: String, logClass: Class[_], message: Any, mdc: MDC, marker: LogMarker) = new Warning3(logSource, logClass, message, mdc, marker)
  }

  /**
   * For INFO Logging
   */
  case class Info(logSource: String, logClass: Class[_], message: Any = "") extends LogEvent {
    override def level = InfoLevel
  }
  class Info2(logSource: String, logClass: Class[_], message: Any, override val mdc: MDC) extends Info(logSource, logClass, message)
  class Info3(logSource: String, logClass: Class[_], message: Any, override val mdc: MDC, override val marker: LogMarker)
    extends Info2(logSource, logClass, message, mdc) with LogEventWithMarker
  object Info {
    def apply(logSource: String, logClass: Class[_], message: Any, mdc: MDC) = new Info2(logSource, logClass, message, mdc)
    def apply(logSource: String, logClass: Class[_], message: Any, mdc: MDC, marker: LogMarker) = new Info3(logSource, logClass, message, mdc, marker)
  }

  /**
   * For DEBUG Logging
   */
  case class Debug(logSource: String, logClass: Class[_], message: Any = "") extends LogEvent {
    override def level = DebugLevel
  }
  class Debug2(logSource: String, logClass: Class[_], message: Any, override val mdc: MDC) extends Debug(logSource, logClass, message)
  class Debug3(logSource: String, logClass: Class[_], message: Any, override val mdc: MDC, override val marker: LogMarker)
    extends Debug2(logSource, logClass, message, mdc) with LogEventWithMarker
  object Debug {
    def apply(logSource: String, logClass: Class[_], message: Any, mdc: MDC) = new Debug2(logSource, logClass, message, mdc)
    def apply(logSource: String, logClass: Class[_], message: Any, mdc: MDC, marker: LogMarker) = new Debug3(logSource, logClass, message, mdc, marker)
  }

  /** INTERNAL API, Marker interface for LogEvents containing Markers, which can be set for example on an slf4j logger */
  sealed trait LogEventWithMarker extends LogEvent {
    def marker: LogMarker
    /** Appends the marker to the Debug/Info/Warning/Error toString representations */
    override def toString = {
      val s = super.toString
      s.substring(0, s.length - 1) + "," + marker + ")"
    }
  }

  /**
   * Message which is sent to each default logger (i.e. from configuration file)
   * after its creation but before attaching it to the logging bus. The logger
   * actor must handle this message, it can be used e.g. to register for more
   * channels. When done, the logger must respond with a LoggerInitialized
   * message. This is necessary to ensure that additional subscriptions are in
   * effect when the logging system finished starting.
   */
  final case class InitializeLogger(bus: LoggingBus) extends NoSerializationVerificationNeeded

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

    import StdOutLogger._
    import java.text.SimpleDateFormat
    import java.util.Date

    private val date = new Date()
    private val dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.SSS")

    // format: OFF
    // FIXME: remove those when we have the chance to break binary compatibility
    private val errorFormat             = ErrorFormat
    private val errorFormatWithoutCause = ErrorFormatWithoutCause
    private val warningFormat           = WarningFormat
    private val infoFormat              = InfoFormat
    private val debugFormat             = DebugFormat
    // format: ON

    def timestamp(event: LogEvent): String = synchronized {
      date.setTime(event.timestamp)
      dateFormat.format(date)
    } // SDF isn't threadsafe

    def print(event: Any): Unit = event match {
      case e: Error   ⇒ error(e)
      case e: Warning ⇒ warning(e)
      case e: Info    ⇒ info(e)
      case e: Debug   ⇒ debug(e)
      case e          ⇒ warning(Warning(simpleName(this), this.getClass, "received unexpected event of class " + e.getClass + ": " + e))
    }

    def error(event: Error): Unit = event match {
      case e: Error3 ⇒ // has marker
        val f = if (event.cause == Error.NoCause) ErrorWithoutCauseWithMarkerFormat else ErrorFormatWithMarker
        println(f.format(
          e.marker.name,
          timestamp(event),
          event.thread.getName,
          event.logSource,
          event.message,
          stackTraceFor(event.cause)))
      case _ ⇒
        val f = if (event.cause == Error.NoCause) ErrorFormatWithoutCause else ErrorFormat
        println(f.format(
          timestamp(event),
          event.thread.getName,
          event.logSource,
          event.message,
          stackTraceFor(event.cause)))
    }

    def warning(event: Warning): Unit = event match {
      case e: Warning3 ⇒ // has marker
        println(WarningWithMarkerFormat.format(
          e.marker.name,
          timestamp(event),
          event.thread.getName,
          event.logSource,
          event.message))
      case _ ⇒
        println(WarningFormat.format(
          timestamp(event),
          event.thread.getName,
          event.logSource,
          event.message))
    }

    def info(event: Info): Unit = event match {
      case e: Info3 ⇒ // has marker
        println(InfoWithMarkerFormat.format(
          e.marker.name,
          timestamp(event),
          event.thread.getName,
          event.logSource,
          event.message))
      case _ ⇒
        println(InfoFormat.format(
          timestamp(event),
          event.thread.getName,
          event.logSource,
          event.message))
    }

    def debug(event: Debug): Unit = event match {
      case e: Debug3 ⇒ // has marker
        println(DebugWithMarkerFormat.format(
          e.marker.name,
          timestamp(event),
          event.thread.getName,
          event.logSource,
          event.message))
      case _ ⇒
        println(DebugFormat.format(
          timestamp(event),
          event.thread.getName,
          event.logSource,
          event.message))
    }
  }
  object StdOutLogger {
    // format: OFF
    private final  val ErrorFormat          = "[ERROR] [%s] [%s] [%s] %s%s"
    private final val ErrorFormatWithMarker = "[ERROR] [%s][%s] [%s] [%s] %s%s"

    private final val ErrorFormatWithoutCause           = "[ERROR] [%s] [%s] [%s] %s"
    private final val ErrorWithoutCauseWithMarkerFormat = "[ERROR] [%s][%s] [%s] [%s] %s"

    private final val WarningFormat           = "[WARN] [%s] [%s] [%s] %s"
    private final val WarningWithMarkerFormat = "[WARN] [%s][%s] [%s] [%s] %s"

    private final val InfoFormat           = "[INFO] [%s] [%s] [%s] %s"
    private final val InfoWithMarkerFormat = "[INFO] [%s][%s] [%s] [%s] %s"

    private final val DebugFormat           = "[DEBUG] [%s] [%s] [%s] %s"
    private final val DebugWithMarkerFormat = "[DEBUG] [%s][%s] [%s] [%s] %s"

    // format: ON
  }

  /**
   * Actor-less logging implementation for synchronous logging to standard
   * output. This logger is always attached first in order to be able to log
   * failures during application start-up, even before normal logging is
   * started. Its log level can be defined by configuration setting
   * <code>akka.stdout-loglevel</code>.
   */
  class StandardOutLogger extends MinimalActorRef with StdOutLogger {
    val path: ActorPath = new RootActorPath(Address("akka", "all-systems"), "/StandardOutLogger")
    def provider: ActorRefProvider = throw new UnsupportedOperationException("StandardOutLogger does not provide")
    override val toString = "StandardOutLogger"
    override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit =
      if (message == null) throw new InvalidMessageException("Message is null")
      else print(message)

    @throws(classOf[java.io.ObjectStreamException])
    override protected def writeReplace(): AnyRef = serializedStandardOutLogger
  }

  private val serializedStandardOutLogger = new SerializedStandardOutLogger

  /**
   * INTERNAL API
   */
  @SerialVersionUID(1L) private[akka] class SerializedStandardOutLogger extends Serializable {
    @throws(classOf[java.io.ObjectStreamException])
    private def readResolve(): AnyRef = Logging.StandardOutLogger
  }

  val StandardOutLogger = new StandardOutLogger

  /**
   * Actor wrapper around the standard output logger. If
   * <code>akka.loggers</code> is not set, it defaults to just this
   * logger.
   */
  class DefaultLogger extends Actor with StdOutLogger with RequiresMessageQueue[LoggerMessageQueueSemantics] {
    override def receive: Receive = {
      case InitializeLogger(_) ⇒ sender() ! LoggerInitialized
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

  type MDC = Map[String, Any]

  val emptyMDC: MDC = Map()

}

/**
 * Logging wrapper to make nicer and optimize: provide template versions which
 * evaluate .toString only if the log level is actually enabled. Typically used
 * by obtaining an implementation from the Logging object:
 *
 * {{{
 * val log = Logging(&lt;bus&gt;, &lt;source object&gt;)
 * ...
 * log.info("hello world!")
 * }}}
 *
 * All log-level methods support simple interpolation templates with up to four
 * arguments placed by using <code>{}</code> within the template (first string
 * argument):
 *
 * {{{
 * log.error(exception, "Exception while processing {} in state {}", msg, state)
 * }}}
 */
trait LoggingAdapter {

  type MDC = Logging.MDC
  def mdc = Logging.emptyMDC

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

  /**
   * Log message at error level, including the exception that caused the error.
   * @see [[LoggingAdapter]]
   */
  def error(cause: Throwable, message: String): Unit = { if (isErrorEnabled) notifyError(cause, message) }
  /**
   * Message template with 1 replacement argument.
   * @see [[LoggingAdapter]]
   */
  def error(cause: Throwable, template: String, arg1: Any): Unit = { if (isErrorEnabled) notifyError(cause, format1(template, arg1)) }
  /**
   * Message template with 2 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any): Unit = { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2)) }
  /**
   * Message template with 3 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2, arg3)) }
  /**
   * Message template with 4 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isErrorEnabled) notifyError(cause, format(template, arg1, arg2, arg3, arg4)) }

  /**
   * Log message at error level, without providing the exception that caused the error.
   * @see [[LoggingAdapter]]
   */
  def error(message: String): Unit = { if (isErrorEnabled) notifyError(message) }
  /**
   * Message template with 1 replacement argument.
   * @see [[LoggingAdapter]]
   */
  def error(template: String, arg1: Any): Unit = { if (isErrorEnabled) notifyError(format1(template, arg1)) }
  /**
   * Message template with 2 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def error(template: String, arg1: Any, arg2: Any): Unit = { if (isErrorEnabled) notifyError(format(template, arg1, arg2)) }
  /**
   * Message template with 3 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def error(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3)) }
  /**
   * Message template with 4 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def error(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3, arg4)) }

  /**
   * Log message at warning level.
   * @see [[LoggingAdapter]]
   */
  def warning(message: String): Unit = { if (isWarningEnabled) notifyWarning(message) }
  /**
   * Message template with 1 replacement argument.
   * @see [[LoggingAdapter]]
   */
  def warning(template: String, arg1: Any): Unit = { if (isWarningEnabled) notifyWarning(format1(template, arg1)) }
  /**
   * Message template with 2 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def warning(template: String, arg1: Any, arg2: Any): Unit = { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2)) }
  /**
   * Message template with 3 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3)) }
  /**
   * Message template with 4 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3, arg4)) }

  /**
   * Log message at info level.
   * @see [[LoggingAdapter]]
   */
  def info(message: String) { if (isInfoEnabled) notifyInfo(message) }
  /**
   * Message template with 1 replacement argument.
   * @see [[LoggingAdapter]]
   */
  def info(template: String, arg1: Any): Unit = { if (isInfoEnabled) notifyInfo(format1(template, arg1)) }
  /**
   * Message template with 2 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def info(template: String, arg1: Any, arg2: Any): Unit = { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2)) }
  /**
   * Message template with 3 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def info(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3)) }
  /**
   * Message template with 4 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def info(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3, arg4)) }

  /**
   * Log message at debug level.
   * @see [[LoggingAdapter]]
   */
  def debug(message: String) { if (isDebugEnabled) notifyDebug(message) }
  /**
   * Message template with 1 replacement argument.
   * @see [[LoggingAdapter]]
   */
  def debug(template: String, arg1: Any): Unit = { if (isDebugEnabled) notifyDebug(format1(template, arg1)) }
  /**
   * Message template with 2 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def debug(template: String, arg1: Any, arg2: Any): Unit = { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2)) }
  /**
   * Message template with 3 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3)) }
  /**
   * Message template with 4 replacement arguments.
   * @see [[LoggingAdapter]]
   */
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3, arg4)) }

  /**
   * Log message at the specified log level.
   */
  def log(level: Logging.LogLevel, message: String) { if (isEnabled(level)) notifyLog(level, message) }
  /**
   * Message template with 1 replacement argument.
   */
  def log(level: Logging.LogLevel, template: String, arg1: Any): Unit = { if (isEnabled(level)) notifyLog(level, format1(template, arg1)) }
  /**
   * Message template with 2 replacement arguments.
   */
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any): Unit = { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2)) }
  /**
   * Message template with 3 replacement arguments.
   */
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2, arg3)) }
  /**
   * Message template with 4 replacement arguments.
   */
  def log(level: Logging.LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = { if (isEnabled(level)) notifyLog(level, format(template, arg1, arg2, arg3, arg4)) }

  /**
   * @return true if the specified log level is enabled
   */
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
    val sb = new java.lang.StringBuilder(64)
    var p = 0
    var startIndex = 0
    while (p < arg.length) {
      val index = t.indexOf("{}", startIndex)
      if (index == -1) {
        sb.append(t.substring(startIndex, t.length))
          .append(" WARNING arguments left: ")
          .append(arg.length - p)
        p = arg.length
        startIndex = t.length
      } else {
        sb.append(t.substring(startIndex, index))
          .append(arg(p))
        startIndex = index + 2
        p += 1
      }
    }
    sb.append(t.substring(startIndex, t.length)).toString
  }
}

/**
 * Filter of log events that is used by the `LoggingAdapter` before
 * publishing log events to the `eventStream`. It can perform
 * fine grained filtering based on the log source.
 *
 * Note that the [[EventStream]] will only subscribe `loggers` to the events
 * corresponding to the `logLevel` of the `EventStream`. Therefore it is good
 * practice that the `LoggingFilter` implementation first filters using the
 * `logLevel` of the `EventStream` before applying more fine grained filters.
 */
trait LoggingFilter {
  def isErrorEnabled(logClass: Class[_], logSource: String): Boolean
  def isWarningEnabled(logClass: Class[_], logSource: String): Boolean
  def isInfoEnabled(logClass: Class[_], logSource: String): Boolean
  def isDebugEnabled(logClass: Class[_], logSource: String): Boolean
}

/**
 * Default [[LoggingFilter]] that uses the logLevel of the `eventStream`, which
 * initial value is defined in configuration. The logLevel `eventStream` can be
 * changed while the system is running.
 */
class DefaultLoggingFilter(logLevel: () ⇒ Logging.LogLevel) extends LoggingFilter {

  def this(settings: Settings, eventStream: EventStream) = this(() ⇒ eventStream.logLevel)

  import Logging._
  def isErrorEnabled(logClass: Class[_], logSource: String) = logLevel() >= ErrorLevel
  def isWarningEnabled(logClass: Class[_], logSource: String) = logLevel() >= WarningLevel
  def isInfoEnabled(logClass: Class[_], logSource: String) = logLevel() >= InfoLevel
  def isDebugEnabled(logClass: Class[_], logSource: String) = logLevel() >= DebugLevel
}

/**
 * LoggingAdapter extension which adds MDC support.
 * Only recommended to be used within Actors as it isn't thread safe.
 */
trait DiagnosticLoggingAdapter extends LoggingAdapter {

  import java.{ util ⇒ ju }

  import Logging._

  import scala.collection.JavaConverters._

  private var _mdc = emptyMDC

  /**
   * Scala API:
   * Mapped Diagnostic Context for application defined values
   * which can be used in PatternLayout when `akka.event.slf4j.Slf4jLogger` is configured.
   * Visit <a href="http://logback.qos.ch/manual/mdc.html">Logback Docs: MDC</a> for more information.
   *
   * @return A Map containing the MDC values added by the application, or empty Map if no value was added.
   */
  override def mdc: MDC = _mdc

  /**
   * Scala API:
   * Sets the values to be added to the MDC (Mapped Diagnostic Context) before the log is appended.
   * These values can be used in PatternLayout when `akka.event.slf4j.Slf4jLogger` is configured.
   * Visit <a href="http://logback.qos.ch/manual/mdc.html">Logback Docs: MDC</a> for more information.
   */
  def mdc(mdc: MDC): Unit = _mdc = if (mdc != null) mdc else emptyMDC

  /**
   * Java API:
   * Mapped Diagnostic Context for application defined values
   * which can be used in PatternLayout when `akka.event.slf4j.Slf4jLogger` is configured.
   * Visit <a href="http://logback.qos.ch/manual/mdc.html">Logback Docs: MDC</a> for more information.
   * Note tha it returns a <b>COPY</b> of the actual MDC values.
   * You cannot modify any value by changing the returned Map.
   * Code like the following won't have any effect unless you set back the modified Map.
   *
   * {{{
   *   Map mdc = log.getMDC();
   *   mdc.put("key", value);
   *   // NEEDED
   *   log.setMDC(mdc);
   * }}}
   *
   * @return A copy of the actual MDC values
   */
  def getMDC: ju.Map[String, Any] = mdc.asJava

  /**
   * Java API:
   * Sets the values to be added to the MDC (Mapped Diagnostic Context) before the log is appended.
   * These values can be used in PatternLayout when `akka.event.slf4j.Slf4jLogger` is configured.
   * Visit <a href="http://logback.qos.ch/manual/mdc.html">Logback Docs: MDC</a> for more information.
   */
  def setMDC(jMdc: java.util.Map[String, Any]): Unit = mdc(if (jMdc != null) jMdc.asScala.toMap else emptyMDC)

  /**
   * Clear all entries in the MDC
   */
  def clearMDC(): Unit = mdc(emptyMDC)
}

final class LogMarker(val name: String)
object LogMarker {
  /** The Marker is internally transferred via MDC using using this key */
  private[akka] final val MDCKey = "marker"

  def apply(name: String): LogMarker = new LogMarker(name)
  /** Java API */
  def create(name: String): LogMarker = apply(name)

  def extractFromMDC(mdc: MDC): Option[String] =
    mdc.get(MDCKey) match {
      case Some(v) ⇒ Some(v.toString)
      case None    ⇒ None
    }

  private[akka] final val Security = apply("SECURITY")

}

/**
 * [[LoggingAdapter]] extension which adds Marker support.
 * Only recommended to be used within Actors as it isn't thread safe.
 */
class MarkerLoggingAdapter(
  override val bus:       LoggingBus,
  override val logSource: String,
  override val logClass:  Class[_],
  loggingFilter:          LoggingFilter)
  extends BusLogging(bus, logSource, logClass, loggingFilter) {
  // TODO when breaking binary compatibility, these marker methods should become baked into LoggingAdapter itself

  // For backwards compatibility, and when LoggingAdapter is created without direct
  // association to an ActorSystem
  def this(bus: LoggingBus, logSource: String, logClass: Class[_]) =
    this(bus, logSource, logClass, new DefaultLoggingFilter(() ⇒ bus.logLevel))

  /**
   * Log message at error level, including the exception that caused the error.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, cause: Throwable, message: String): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, message, mdc, marker))

  /**
   * Message template with 1 replacement argument.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, format(template, arg1), mdc, marker))

  /**
   * Message template with 2 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, format(template, arg1, arg2), mdc, marker))

  /**
   * Message template with 3 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, format(template, arg1, arg2, arg3), mdc, marker))

  /**
   * Message template with 4 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, format(template, arg1, arg2, arg3, arg4), mdc, marker))

  /**
   * Log message at error level, without providing the exception that caused the error.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, message: String): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, message, mdc, marker))

  /**
   * Message template with 1 replacement argument.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, template: String, arg1: Any): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, format(template, arg1), mdc, marker))

  /**
   * Message template with 2 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, format(template, arg1, arg2), mdc, marker))

  /**
   * Message template with 3 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, format(template, arg1, arg2, arg3), mdc, marker))

  /**
   * Message template with 4 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def error(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit =
    if (isErrorEnabled) bus.publish(Error(logSource, logClass, format(template, arg1, arg2, arg3, arg4), mdc, marker))

  /**
   * Log message at warning level.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def warning(marker: LogMarker, message: String): Unit =
    if (isWarningEnabled) bus.publish(Warning(logSource, logClass, message, mdc, marker))

  /**
   * Message template with 1 replacement argument.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def warning(marker: LogMarker, template: String, arg1: Any): Unit =
    if (isWarningEnabled) bus.publish(Warning(logSource, logClass, format(template, arg1), mdc, marker))

  /**
   * Message template with 2 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit =
    if (isWarningEnabled) bus.publish(Warning(logSource, logClass, format(template, arg1, arg2), mdc, marker))

  /**
   * Message template with 3 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit =
    if (isWarningEnabled) bus.publish(Warning(logSource, logClass, format(template, arg1, arg2, arg3), mdc, marker))

  /**
   * Message template with 4 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit =
    if (isWarningEnabled) bus.publish(Warning(logSource, logClass, format(template, arg1, arg2, arg3, arg4), mdc, marker))

  /**
   * Log message at info level.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def info(marker: LogMarker, message: String): Unit =
    if (isInfoEnabled) bus.publish(Info(logSource, logClass, message, mdc, marker))

  /**
   * Message template with 1 replacement argument.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def info(marker: LogMarker, template: String, arg1: Any): Unit =
    if (isInfoEnabled) bus.publish(Info(logSource, logClass, format(template, arg1), mdc, marker))

  /**
   * Message template with 2 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def info(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit =
    if (isInfoEnabled) bus.publish(Info(logSource, logClass, format(template, arg1, arg2), mdc, marker))

  /**
   * Message template with 3 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def info(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit =
    if (isInfoEnabled) bus.publish(Info(logSource, logClass, format(template, arg1, arg2, arg3), mdc, marker))

  /**
   * Message template with 4 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def info(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit =
    if (isInfoEnabled) bus.publish(Info(logSource, logClass, format(template, arg1, arg2, arg3, arg4), mdc, marker))

  /**
   * Log message at debug level.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def debug(marker: LogMarker, message: String): Unit =
    if (isDebugEnabled) bus.publish(Debug(logSource, logClass, message, mdc, marker))

  /**
   * Message template with 1 replacement argument.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def debug(marker: LogMarker, template: String, arg1: Any): Unit =
    if (isDebugEnabled) bus.publish(Debug(logSource, logClass, format(template, arg1), mdc, marker))

  /**
   * Message template with 2 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit =
    if (isDebugEnabled) bus.publish(Debug(logSource, logClass, format(template, arg1, arg2), mdc, marker))

  /**
   * Message template with 3 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit =
    if (isDebugEnabled) bus.publish(Debug(logSource, logClass, format(template, arg1, arg2, arg3), mdc, marker))

  /**
   * Message template with 4 replacement arguments.
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[LoggingAdapter]]
   */
  def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit =
    if (isDebugEnabled) bus.publish(Debug(logSource, logClass, format(template, arg1, arg2, arg3, arg4), mdc, marker))

}

final class DiagnosticMarkerBusLoggingAdapter(
  override val bus:       LoggingBus,
  override val logSource: String,
  override val logClass:  Class[_],
  loggingFilter:          LoggingFilter)
  extends MarkerLoggingAdapter(bus, logSource, logClass, loggingFilter) with DiagnosticLoggingAdapter

/**
 * [[akka.event.LoggingAdapter]] that publishes [[akka.event.Logging.LogEvent]] to event stream.
 */
class BusLogging(val bus: LoggingBus, val logSource: String, val logClass: Class[_], loggingFilter: LoggingFilter)
  extends LoggingAdapter {

  // For backwards compatibility, and when LoggingAdapter is created without direct
  // association to an ActorSystem
  def this(bus: LoggingBus, logSource: String, logClass: Class[_]) =
    this(bus, logSource, logClass, new DefaultLoggingFilter(() ⇒ bus.logLevel))

  import Logging._

  def isErrorEnabled = loggingFilter.isErrorEnabled(logClass, logSource)
  def isWarningEnabled = loggingFilter.isWarningEnabled(logClass, logSource)
  def isInfoEnabled = loggingFilter.isInfoEnabled(logClass, logSource)
  def isDebugEnabled = loggingFilter.isDebugEnabled(logClass, logSource)

  protected def notifyError(message: String): Unit =
    bus.publish(Error(logSource, logClass, message, mdc))
  protected def notifyError(cause: Throwable, message: String): Unit =
    bus.publish(Error(cause, logSource, logClass, message, mdc))
  protected def notifyWarning(message: String): Unit =
    bus.publish(Warning(logSource, logClass, message, mdc))
  protected def notifyInfo(message: String): Unit =
    bus.publish(Info(logSource, logClass, message, mdc))
  protected def notifyDebug(message: String): Unit =
    bus.publish(Debug(logSource, logClass, message, mdc))
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
/**
 * NoLogging is a MarkerLoggingAdapter that does absolutely nothing – no logging at all.
 */
object NoMarkerLogging extends MarkerLoggingAdapter(null, "source", classOf[String], null) {

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
  final override def error(marker: LogMarker, cause: Throwable, message: String): Unit = ()
  final override def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any): Unit = ()
  final override def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any): Unit = ()
  final override def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  final override def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = ()
  final override def error(marker: LogMarker, message: String): Unit = ()
  final override def error(marker: LogMarker, template: String, arg1: Any): Unit = ()
  final override def error(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = ()
  final override def error(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  final override def error(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = ()
  final override def warning(marker: LogMarker, message: String): Unit = ()
  final override def warning(marker: LogMarker, template: String, arg1: Any): Unit = ()
  final override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = ()
  final override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  final override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = ()
  final override def info(marker: LogMarker, message: String): Unit = ()
  final override def info(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = ()
  final override def info(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  final override def info(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = ()
  final override def debug(marker: LogMarker, message: String): Unit = ()
  final override def debug(marker: LogMarker, template: String, arg1: Any): Unit = ()
  final override def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = ()
  final override def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
  final override def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = ()
}
