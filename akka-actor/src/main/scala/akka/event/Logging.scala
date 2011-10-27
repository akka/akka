/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor.{ Actor, ActorRef, MinimalActorRef, LocalActorRef, Props, UntypedChannel }
import akka.{ AkkaException, AkkaApplication }
import akka.AkkaApplication.AkkaConfig
import akka.util.ReflectiveAccess
import akka.config.ConfigurationException

/**
 * This trait brings log level handling to the MainBus: it reads the log
 * levels for the initial logging (StandardOutLogger) and the loggers&level
 * for after-init logging, possibly keeping the StandardOutLogger enabled if
 * it is part of the configured loggers. All configured loggers are treated as
 * system services and managed by this trait, i.e. subscribed/unsubscribed in
 * response to changes of LoggingBus.logLevel.
 */
trait LoggingBus extends ActorEventBus {

  type Event >: Logging.LogEvent
  type Classifier >: Class[_]

  import Logging._

  private var loggers = Seq.empty[ActorRef]
  @volatile
  private var _logLevel: LogLevel = _

  def logLevel = _logLevel

  def logLevel_=(level: LogLevel) {
    for { l ← AllLogLevels if l > _logLevel && l <= level; log ← loggers } subscribe(log, classFor(l))
    for { l ← AllLogLevels if l <= _logLevel && l > level; log ← loggers } unsubscribe(log, classFor(l))
    _logLevel = level
  }

  def startStdoutLogger(config: AkkaConfig) {
    val level = levelFor(config.StdoutLogLevel) getOrElse {
      StandardOutLogger.print(Error(new EventHandlerException, this, "unknown akka.stdout-loglevel " + config.StdoutLogLevel))
      ErrorLevel
    }
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(StandardOutLogger, classFor(l)))
    loggers = Seq(StandardOutLogger)
    _logLevel = level
    publish(Info(this, "StandardOutLogger started"))
  }

  def startDefaultLoggers(app: AkkaApplication, config: AkkaConfig) {
    val level = levelFor(config.LogLevel) getOrElse {
      StandardOutLogger.print(Error(new EventHandlerException, this, "unknown akka.stdout-loglevel " + config.LogLevel))
      ErrorLevel
    }
    try {
      val defaultLoggers = config.EventHandlers match {
        case Nil     ⇒ "akka.event.Logging$DefaultLogger" :: Nil
        case loggers ⇒ loggers
      }
      loggers = for {
        loggerName ← defaultLoggers
        if loggerName != DefaultLoggerName
      } yield {
        try {
          ReflectiveAccess.getClassFor[Actor](loggerName) match {
            case Right(actorClass) ⇒ addLogger(app, actorClass, level)
            case Left(exception)   ⇒ throw exception
          }
        } catch {
          case e: Exception ⇒
            throw new ConfigurationException(
              "Event Handler specified in config can't be loaded [" + loggerName +
                "] due to [" + e.toString + "]", e)
        }
      }
      publish(Info(this, "Default Loggers started"))
      if (defaultLoggers contains DefaultLoggerName) {
        loggers :+= StandardOutLogger
      } else {
        unsubscribe(StandardOutLogger)
      }
      _logLevel = level
    } catch {
      case e: Exception ⇒
        System.err.println("error while starting up EventHandler")
        e.printStackTrace()
        throw new ConfigurationException("Could not start Event Handler due to [" + e.toString + "]")
    }
  }

  private def addLogger(app: AkkaApplication, clazz: Class[_ <: Actor], level: LogLevel): ActorRef = {
    val actor = app.systemActorOf(Props(clazz), Props.randomAddress)
    actor ! InitializeLogger(this)
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(actor, classFor(l)))
    publish(Info(this, "logger " + clazz.getName + " started"))
    actor
  }

}

object Logging {

  trait LogLevelType
  type LogLevel = Int with LogLevelType
  final val ErrorLevel = 1.asInstanceOf[Int with LogLevelType]
  final val WarningLevel = 2.asInstanceOf[Int with LogLevelType]
  final val InfoLevel = 3.asInstanceOf[Int with LogLevelType]
  final val DebugLevel = 4.asInstanceOf[Int with LogLevelType]

  def levelFor(s: String): Option[LogLevel] = s match {
    case "ERROR" | "error"     ⇒ Some(ErrorLevel)
    case "WARNING" | "warning" ⇒ Some(WarningLevel)
    case "INFO" | "info"       ⇒ Some(InfoLevel)
    case "DEBUG" | "debug"     ⇒ Some(DebugLevel)
    case unknown               ⇒ None
  }

  def levelFor(eventClass: Class[_ <: LogEvent]) = {
    if (classOf[Error].isAssignableFrom(eventClass)) ErrorLevel
    else if (classOf[Warning].isAssignableFrom(eventClass)) WarningLevel
    else if (classOf[Info].isAssignableFrom(eventClass)) InfoLevel
    else if (classOf[Debug].isAssignableFrom(eventClass)) DebugLevel
    else DebugLevel
  }

  def classFor(level: LogLevel): Class[_ <: LogEvent] = level match {
    case ErrorLevel   ⇒ classOf[Error]
    case WarningLevel ⇒ classOf[Warning]
    case InfoLevel    ⇒ classOf[Info]
    case DebugLevel   ⇒ classOf[Debug]
  }

  val AllLogLevels = Seq(ErrorLevel: AnyRef, WarningLevel, InfoLevel, DebugLevel).asInstanceOf[Seq[LogLevel]]

  val errorFormat = "[ERROR] [%s] [%s] [%s] %s\n%s".intern
  val warningFormat = "[WARN] [%s] [%s] [%s] %s".intern
  val infoFormat = "[INFO] [%s] [%s] [%s] %s".intern
  val debugFormat = "[DEBUG] [%s] [%s] [%s] %s".intern
  val genericFormat = "[GENERIC] [%s] [%s]".intern

  def apply(app: AkkaApplication, instance: AnyRef): Logging = new MainBusLogging(app.mainbus, instance)
  def apply(bus: MainBus, instance: AnyRef): Logging = new MainBusLogging(bus, instance)

  class EventHandlerException extends AkkaException

  sealed trait LogEvent {
    @transient
    val thread: Thread = Thread.currentThread
    def level: LogLevel
  }

  case class Error(cause: Throwable, instance: AnyRef, message: Any = "") extends LogEvent {
    def level = ErrorLevel
  }
  object Error {
    def apply(instance: AnyRef, message: Any) = new Error(new EventHandlerException, instance, message)
  }

  case class Warning(instance: AnyRef, message: Any = "") extends LogEvent {
    def level = WarningLevel
  }

  case class Info(instance: AnyRef, message: Any = "") extends LogEvent {
    def level = InfoLevel
  }

  case class Debug(instance: AnyRef, message: Any = "") extends LogEvent {
    def level = DebugLevel
  }

  case class InitializeLogger(bus: LoggingBus)

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
        case e          ⇒ generic(e)
      }
    }

    def error(event: Error) =
      println(errorFormat.format(
        timestamp,
        event.thread.getName,
        instanceName(event.instance),
        event.message,
        stackTraceFor(event.cause)))

    def warning(event: Warning) =
      println(warningFormat.format(
        timestamp,
        event.thread.getName,
        instanceName(event.instance),
        event.message))

    def info(event: Info) =
      println(infoFormat.format(
        timestamp,
        event.thread.getName,
        instanceName(event.instance),
        event.message))

    def debug(event: Debug) =
      println(debugFormat.format(
        timestamp,
        event.thread.getName,
        instanceName(event.instance),
        event.message))

    def generic(event: Any) =
      println(genericFormat.format(timestamp, event.toString))

    def instanceName(instance: AnyRef): String = instance match {
      case null        ⇒ "NULL"
      case a: ActorRef ⇒ a.address
      case _           ⇒ instance.getClass.getSimpleName
    }
  }

  class StandardOutLogger extends MinimalActorRef with StdOutLogger {
    override val toString = "StandardOutLogger"
    override def postMessageToMailbox(obj: Any, channel: UntypedChannel) { print(obj) }
  }
  val StandardOutLogger = new StandardOutLogger
  val DefaultLoggerName = StandardOutLogger.getClass.getName

  class DefaultLogger extends Actor with StdOutLogger {
    def receive = {
      case InitializeLogger(_) ⇒
      case event: LogEvent     ⇒ print(event)
    }
  }

  def stackTraceFor(e: Throwable) = {
    if (e ne null) {
      import java.io.{ StringWriter, PrintWriter }
      val sw = new StringWriter
      val pw = new PrintWriter(sw)
      e.printStackTrace(pw)
      sw.toString
    } else {
      "[NO STACK TRACE]"
    }
  }

}

/**
 * Logging wrapper to make nicer and optimize: provide template versions which
 * evaluate .toString only if the log level is actually enabled.
 */
trait Logging {

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
  protected def notifyError(cause: Throwable, message: String)
  protected def notifyWarning(message: String)
  protected def notifyInfo(message: String)
  protected def notifyDebug(message: String)

  /*
   * The rest is just the widening of the API for the user's convenience.
   */

  def error(cause: Throwable, message: String) { if (isErrorEnabled) notifyError(cause, message) }
  def error(cause: Throwable, template: String, arg1: Any) { if (isErrorEnabled) error(cause, format(template, arg1)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any) { if (isErrorEnabled) error(cause, format(template, arg1, arg2)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any) { if (isErrorEnabled) error(cause, format(template, arg1, arg2, arg3)) }
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isErrorEnabled) error(cause, format(template, arg1, arg2, arg3, arg4)) }

  def error(message: String) { if (isErrorEnabled) error(null: Throwable, message) }
  def error(template: String, arg1: Any) { if (isErrorEnabled) error(format(template, arg1)) }
  def error(template: String, arg1: Any, arg2: Any) { if (isErrorEnabled) error(format(template, arg1, arg2)) }
  def error(template: String, arg1: Any, arg2: Any, arg3: Any) { if (isErrorEnabled) error(format(template, arg1, arg2, arg3)) }
  def error(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isErrorEnabled) error(format(template, arg1, arg2, arg3, arg4)) }

  def warning(message: String) { if (isWarningEnabled) notifyWarning(message) }
  def warning(template: String, arg1: Any) { if (isWarningEnabled) warning(format(template, arg1)) }
  def warning(template: String, arg1: Any, arg2: Any) { if (isWarningEnabled) warning(format(template, arg1, arg2)) }
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any) { if (isWarningEnabled) warning(format(template, arg1, arg2, arg3)) }
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isWarningEnabled) warning(format(template, arg1, arg2, arg3, arg4)) }

  def info(message: String) { if (isInfoEnabled) notifyInfo(message) }
  def info(template: String, arg1: Any) { if (isInfoEnabled) info(format(template, arg1)) }
  def info(template: String, arg1: Any, arg2: Any) { if (isInfoEnabled) info(format(template, arg1, arg2)) }
  def info(template: String, arg1: Any, arg2: Any, arg3: Any) { if (isInfoEnabled) info(format(template, arg1, arg2, arg3)) }
  def info(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isInfoEnabled) info(format(template, arg1, arg2, arg3, arg4)) }

  def debug(message: String) { if (isDebugEnabled) notifyDebug(message) }
  def debug(template: String, arg1: Any) { if (isDebugEnabled) debug(format(template, arg1)) }
  def debug(template: String, arg1: Any, arg2: Any) { if (isDebugEnabled) debug(format(template, arg1, arg2)) }
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any) { if (isDebugEnabled) debug(format(template, arg1, arg2, arg3)) }
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) { if (isDebugEnabled) debug(format(template, arg1, arg2, arg3, arg4)) }

  def format(t: String, arg1: Any) = t.replaceFirst("{}", arg1.asInstanceOf[AnyRef].toString)
  def format(t: String, arg1: Any, arg2: Any) = t.replaceFirst("{}", arg1.asInstanceOf[AnyRef].toString).replaceFirst("{}", arg2.asInstanceOf[AnyRef].toString)
  def format(t: String, arg1: Any, arg2: Any, arg3: Any) = t.replaceFirst("{}", arg1.asInstanceOf[AnyRef].toString).replaceFirst("{}", arg2.asInstanceOf[AnyRef].toString).replaceFirst("{}", arg3.asInstanceOf[AnyRef].toString)
  def format(t: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any) = t.replaceFirst("{}", arg1.asInstanceOf[AnyRef].toString).replaceFirst("{}", arg2.asInstanceOf[AnyRef].toString).replaceFirst("{}", arg3.asInstanceOf[AnyRef].toString).replaceFirst("{}", arg4.asInstanceOf[AnyRef].toString)

}

class MainBusLogging(val mainbus: MainBus, val loggingInstance: AnyRef) extends Logging {

  import Logging._

  def isErrorEnabled = mainbus.logLevel >= ErrorLevel
  def isWarningEnabled = mainbus.logLevel >= WarningLevel
  def isInfoEnabled = mainbus.logLevel >= InfoLevel
  def isDebugEnabled = mainbus.logLevel >= DebugLevel

  protected def notifyError(cause: Throwable, message: String) { mainbus.publish(Error(cause, loggingInstance, message)) }

  protected def notifyWarning(message: String) { mainbus.publish(Warning(loggingInstance, message)) }

  protected def notifyInfo(message: String) { mainbus.publish(Info(loggingInstance, message)) }

  protected def notifyDebug(message: String) { mainbus.publish(Debug(loggingInstance, message)) }

}