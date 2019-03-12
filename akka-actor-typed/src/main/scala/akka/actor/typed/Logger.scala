/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.util.Optional

import akka.annotation.{ DoNotInherit, InternalApi }
import akka.event.Logging
import akka.event.Logging._

/**
 * A log marker is an additional metadata tag supported by some logging backends to identify "special" log events.
 * In the Akka internal actors for example the "SECURITY" marker is used for warnings related to security.
 *
 * Not for user extension, create instances using factory methods
 */
@DoNotInherit
sealed trait LogMarker {
  def name: String
}

/**
 * Factories for log markers
 */
object LogMarker {

  /**
   * INTERNAL API
   */
  @InternalApi
  private final class LogMarkerImpl(name: String) extends akka.event.LogMarker(name) with LogMarker

  /**
   * Scala API: Create a new log marker with the given name
   */
  def apply(name: String): LogMarker = new LogMarkerImpl(name)

  /**
   * Scala API: Create a new log marker with the given name
   */
  def create(name: String): LogMarker = apply(name)

}

/**
 * Logging options when using `Behaviors.logMessages`.
 */
@DoNotInherit
abstract sealed class LogOptions {

  /**
   * User control whether messages are logged or not.  This is useful when you want to have an application configuration
   * to control when to log messages.
   */
  def withEnabled(enabled: Boolean): LogOptions

  /**
   * The [[akka.event.Logging.LogLevel]] to use when logging messages.
   */
  def withLevel(level: LogLevel): LogOptions

  /**
   * A [[akka.actor.typed.Logger]] to use when logging messages.
   */
  def withLogger(logger: Logger): LogOptions

  def enabled: Boolean
  def level: LogLevel
  def logger: Option[Logger]

  /** Java API */
  def getLogger: Optional[Logger]
}

/**
 * Factories for log options
 */
object LogOptions {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] final case class LogOptionsImpl(enabled: Boolean, level: LogLevel, logger: Option[Logger])
      extends LogOptions {

    /**
     * User control whether messages are logged or not.  This is useful when you want to have an application configuration
     * to control when to log messages.
     */
    override def withEnabled(enabled: Boolean): LogOptions = this.copy(enabled = enabled)

    /**
     * The [[akka.event.Logging.LogLevel]] to use when logging messages.
     */
    override def withLevel(level: LogLevel): LogOptions = this.copy(level = level)

    /**
     * A [[akka.actor.typed.Logger]] to use when logging messages.
     */
    override def withLogger(logger: Logger): LogOptions = this.copy(logger = Option(logger))

    /** Java API */
    override def getLogger: Optional[Logger] = Optional.ofNullable(logger.orNull)
  }

  /**
   * Scala API: Create a new log options with defaults.
   */
  def apply(): LogOptions = LogOptionsImpl(enabled = true, Logging.DebugLevel, None)

  /**
   * Java API: Create a new log options.
   */
  def create(): LogOptions = apply()
}

/**
 * Logging API provided inside of actors through the actor context.
 *
 * All log-level methods support simple interpolation templates with up to four
 * arguments placed by using <code>{}</code> within the template (first string
 * argument):
 *
 * {{{
 * ctx.log.error(exception, "Exception while processing {} in state {}", msg, state)
 * }}}
 *
 * More than four arguments can be defined by using an `Array` with the method with
 * one argument parameter.
 *
 * *Rationale for an Akka-specific logging API:*
 * Provided rather than a specific logging library logging API to not enforce a specific logging library on users but
 * still providing a convenient, performant, asynchronous and testable logging solution. Additionally it allows unified
 * logging for both user implemented actors and built in Akka actors where the actual logging backend can be selected
 * by the user. This logging facade is also used by Akka internally, without having to depend on specific logging frameworks.
 *
 * The [[Logger]] of an actor is tied to the actor path and should not be shared with other threads outside of the actor.
 *
 * Not for user extension
 */
@DoNotInherit
abstract class Logger private[akka] () {

  /**
   * Whether error logging is enabled on the actor system level, may not represent the setting all the way to the
   * logger implementation, but when it does it allows avoiding unnecessary resource usage for log entries that
   * will not actually end up in any logger output.
   */
  def isErrorEnabled: Boolean

  /**
   * Whether error logging with this marker is enabled on the actor system level, may not represent the setting all
   * the way to the logger implementation, but when it does it allows avoiding unnecessary resource usage for log
   * entries that will not actually end up in any logger output.
   */
  def isErrorEnabled(marker: LogMarker): Boolean

  /**
   * Whether warning logging is enabled on the actor system level, may not represent the setting all the way to the
   * logger implementation, but when it does it allows avoiding unnecessary resource usage for log entries that
   * will not actually end up in any logger output.
   */
  def isWarningEnabled: Boolean

  /**
   * Whether warning logging with this marker is enabled on the actor system level, may not represent the setting all
   * the way to the logger implementation, but when it does it allows avoiding unnecessary resource usage for log
   * entries that will not actually end up in any logger output.
   */
  def isWarningEnabled(marker: LogMarker): Boolean

  /**
   * Whether info logging is enabled on the actor system level, may not represent the setting all the way to the
   * logger implementation, but when it does it allows avoiding unnecessary resource usage for log entries that
   * will not actually end up in any logger output.
   */
  def isInfoEnabled: Boolean

  /**
   * Whether info logging with this marker is enabled on the actor system level, may not represent the setting all
   * the way to the logger implementation, but when it does it allows avoiding unnecessary resource usage for log
   * entries that will not actually end up in any logger output.
   */
  def isInfoEnabled(marker: LogMarker): Boolean

  /**
   * Whether debug logging is enabled on the actor system level, may not represent the setting all the way to the
   * logger implementation, but when it does it allows avoiding unnecessary resource usage for log entries that
   * will not actually end up in any logger output.
   */
  def isDebugEnabled: Boolean

  /**
   * Whether debug logging with this marker is enabled on the actor system level, may not represent the setting all
   * the way to the logger implementation, but when it does it allows avoiding unnecessary resource usage for log
   * entries that will not actually end up in any logger output.
   */
  def isDebugEnabled(marker: LogMarker): Boolean

  /**
   * Whether a log level is enabled on the actor system level, may not represent the setting all the way to the
   * logger implementation, but when it does it allows avoiding unnecessary resource usage for log entries that
   * will not actually end up in any logger output.
   */
  def isLevelEnabled(logLevel: LogLevel): Boolean = logLevel match {
    case ErrorLevel   => isErrorEnabled
    case WarningLevel => isWarningEnabled
    case InfoLevel    => isInfoEnabled
    case DebugLevel   => isDebugEnabled
    case _            => false
  }

  /**
   * Whether a log level with this marker is enabled on the actor system level, may not represent the setting all the
   * way to the logger implementation, but when it does it allows avoiding unnecessary resource usage for log entries
   * that will not actually end up in any logger output.
   */
  def isLevelEnabled(logLevel: LogLevel, marker: LogMarker): Boolean = logLevel match {
    case ErrorLevel   => isErrorEnabled(marker)
    case WarningLevel => isWarningEnabled(marker)
    case InfoLevel    => isInfoEnabled(marker)
    case DebugLevel   => isDebugEnabled(marker)
    case _            => false
  }

  // message only error logging

  /**
   * Log message at error level, without providing the exception that caused the error.
   *
   * @see [[Logger]]
   */
  def error(message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def error(template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * @see [[Logger]]
   */
  def error(template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * @see [[Logger]]
   */
  def error(template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * @see [[Logger]]
   */
  def error(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // exception error logging

  /**
   * Log message at error level, including the exception that caused the error.
   *
   * @see [[Logger]]
   */
  def error(cause: Throwable, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def error(cause: Throwable, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * @see [[Logger]]
   */
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * @see [[Logger]]
   */
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * @see [[Logger]]
   */
  def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // marker error logging

  /**
   * Log message at error level, including the exception that caused the error.
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, cause: Throwable, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  /**
   * Log message at error level, without providing the exception that caused the error.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def error(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // message only warning logging

  /**
   * Log message at warning level.
   */
  def warning(message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def warning(template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * @see [[Logger]]
   */
  def warning(template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * @see [[Logger]]
   */
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * @see [[Logger]]
   */
  def warning(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  /**
   * Log message at warning level.
   */
  def warning(cause: Throwable, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def warning(cause: Throwable, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   * @see [[Logger]]
   */
  def warning(cause: Throwable, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   * @see [[Logger]]
   */
  def warning(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   * @see [[Logger]]
   */
  def warning(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // marker warning logging
  /**
   * Log message at warning level.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   */
  def warning(marker: LogMarker, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  /**
   * Log message at warning level.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, cause: Throwable, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, cause: Throwable, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def warning(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // message only info logging

  /**
   * Log message at info level.
   *
   * @see [[Logger]]
   */
  def info(message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def info(template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * @see [[Logger]]
   */
  def info(template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * @see [[Logger]]
   */
  def info(template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * @see [[Logger]]
   */
  def info(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // marker info logging

  /**
   * Log message at info level.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def info(marker: LogMarker, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def info(marker: LogMarker, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def info(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def info(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def info(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // message only debug logging

  /**
   * Log message at debug level.
   *
   * @see [[Logger]]
   */
  def debug(message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def debug(template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * @see [[Logger]]
   */
  def debug(template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * @see [[Logger]]
   */
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * @see [[Logger]]
   */
  def debug(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // marker debug logging

  /**
   * Log message at debug level.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def debug(marker: LogMarker, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def debug(marker: LogMarker, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // message any level logging

  /**
   * Log message at the specified level.
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  // marker logging at any level

  /**
   * Log message at the specified level.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, marker: LogMarker, message: String): Unit

  /**
   * Message template with 1 replacement argument.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * If `arg1` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, marker: LogMarker, template: String, arg1: Any): Unit

  /**
   * Message template with 2 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit

  /**
   * Message template with 3 replacement arguments.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit

  /**
   * Message template with 4 replacement arguments. For more parameters see the single replacement version of this method.
   *
   * The marker argument can be picked up by various logging frameworks such as slf4j to mark this log statement as "special".
   *
   * @see [[Logger]]
   */
  def log(level: LogLevel, marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit

  /**
   * Scala API: the returned logger will add the given MDC (Mapped Diagnostic Context) to any log entry logged
   *
   * See also [[akka.actor.typed.scaladsl.Behaviors.withMdc]]
   */
  def withMdc(mdc: Map[String, Any]): Logger

  /**
   * Java API: the returned logger will add the given MDC (Mapped Diagnostic Context) to any log entry logged
   *
   * See also [[akka.actor.typed.javadsl.Behaviors.withMdc]]
   */
  def withMdc(mdc: java.util.Map[String, Any]): Logger

  /**
   * Return a new logger sharing properties of this logger except the logger class
   */
  def withLoggerClass(clazz: Class[_]): Logger

  /**
   * Return a new logger sharing properties of this logger except the log source
   */
  def withLogSource(logSource: String): Logger
}
