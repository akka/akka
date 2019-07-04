/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import akka.actor.typed.{ LogMarker, Logger }
import akka.annotation.InternalApi
import akka.event.Logging._
import akka.event.{ LoggingBus, LoggingFilterWithMarker, LogMarker => UntypedLM }
import akka.util.OptionVal

import akka.util.ccompat.JavaConverters._

/**
 * INTERNAL API
 */
private[akka] abstract class AbstractLogger extends Logger {

  // actual log entry emitting methods
  private[akka] def notifyError(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit
  private[akka] def notifyWarning(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit
  private[akka] def notifyInfo(message: String, marker: OptionVal[LogMarker]): Unit
  private[akka] def notifyDebug(message: String, marker: OptionVal[LogMarker]): Unit
  // is set directly by Behaviors.withMdc
  private[akka] var mdc: Map[String, Any] = Map.empty

  // user api implementations
  override def withMdc(mdc: java.util.Map[String, Any]): Logger =
    withMdc(mdc.asScala.toMap)

  override def error(message: String): Unit = {
    if (isErrorEnabled) notifyError(message, OptionVal.None, OptionVal.None)
  }

  override def error(template: String, arg1: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1), OptionVal.None, OptionVal.None)
  }

  override def error(template: String, arg1: Any, arg2: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2), OptionVal.None, OptionVal.None)
  }

  override def error(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3), OptionVal.None, OptionVal.None)
  }

  override def error(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3, arg4), OptionVal.None, OptionVal.None)
  }

  override def error(cause: Throwable, message: String): Unit = {
    if (isErrorEnabled) notifyError(message, OptionVal.Some(cause), OptionVal.None)
  }

  override def error(cause: Throwable, template: String, arg1: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1), OptionVal.Some(cause), OptionVal.None)
  }

  override def error(cause: Throwable, template: String, arg1: Any, arg2: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2), OptionVal.Some(cause), OptionVal.None)
  }

  override def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3), OptionVal.Some(cause), OptionVal.None)
  }

  override def error(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3, arg4), OptionVal.Some(cause), OptionVal.None)
  }

  override def error(marker: LogMarker, cause: Throwable, message: String): Unit = {
    if (isErrorEnabled) notifyError(message, OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1), OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2), OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3), OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def error(
      marker: LogMarker,
      cause: Throwable,
      template: String,
      arg1: Any,
      arg2: Any,
      arg3: Any,
      arg4: Any): Unit = {
    if (isErrorEnabled)
      notifyError(format(template, arg1, arg2, arg3, arg4), OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def error(marker: LogMarker, message: String): Unit = {
    if (isErrorEnabled) notifyError(message, OptionVal.None, OptionVal.Some(marker))
  }

  override def error(marker: LogMarker, template: String, arg1: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1), OptionVal.None, OptionVal.Some(marker))
  }

  override def error(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2), OptionVal.None, OptionVal.Some(marker))
  }

  override def error(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3), OptionVal.None, OptionVal.Some(marker))
  }

  override def error(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3, arg4), OptionVal.None, OptionVal.Some(marker))
  }

  override def warning(message: String): Unit = {
    if (isWarningEnabled) notifyWarning(message, OptionVal.None, OptionVal.None)
  }

  override def warning(template: String, arg1: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1), OptionVal.None, OptionVal.None)
  }

  override def warning(template: String, arg1: Any, arg2: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2), OptionVal.None, OptionVal.None)
  }

  override def warning(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3), OptionVal.None, OptionVal.None)
  }

  override def warning(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3, arg4), OptionVal.None, OptionVal.None)
  }

  override def warning(cause: Throwable, message: String): Unit = {
    if (isWarningEnabled) notifyWarning(message, OptionVal.Some(cause), OptionVal.None)
  }

  override def warning(cause: Throwable, template: String, arg1: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1), OptionVal.Some(cause), OptionVal.None)
  }

  override def warning(cause: Throwable, template: String, arg1: Any, arg2: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2), OptionVal.Some(cause), OptionVal.None)
  }

  override def warning(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3), OptionVal.Some(cause), OptionVal.None)
  }

  override def warning(cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3, arg4), OptionVal.Some(cause), OptionVal.None)
  }

  override def warning(marker: LogMarker, cause: Throwable, template: String, arg1: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1), OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2), OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isWarningEnabled)
      notifyWarning(format(template, arg1, arg2, arg3), OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def warning(
      marker: LogMarker,
      cause: Throwable,
      template: String,
      arg1: Any,
      arg2: Any,
      arg3: Any,
      arg4: Any): Unit = {
    if (isWarningEnabled)
      notifyWarning(format(template, arg1, arg2, arg3, arg4), OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, cause: Throwable, message: String): Unit = {
    if (isWarningEnabled) notifyWarning(message, OptionVal.Some(cause), OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, message: String): Unit = {
    if (isWarningEnabled) notifyWarning(message, OptionVal.None, OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, template: String, arg1: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1), OptionVal.None, OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2), OptionVal.None, OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3), OptionVal.None, OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isWarningEnabled)
      notifyWarning(format(template, arg1, arg2, arg3, arg4), OptionVal.None, OptionVal.Some(marker))
  }

  override def info(message: String): Unit = {
    if (isInfoEnabled) notifyInfo(message, OptionVal.None)
  }

  override def info(template: String, arg1: Any): Unit = {
    if (isInfoEnabled) notifyInfo(format(template, arg1), OptionVal.None)
  }

  override def info(template: String, arg1: Any, arg2: Any): Unit = {
    if (isInfoEnabled) notifyInfo(format(template, arg1, arg2), OptionVal.None)
  }

  override def info(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3), OptionVal.None)
  }

  override def info(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3, arg4), OptionVal.None)
  }

  override def info(marker: LogMarker, message: String): Unit = {
    if (isInfoEnabled) notifyInfo(message, OptionVal.Some(marker))
  }

  override def info(marker: LogMarker, template: String, arg1: Any): Unit = {
    if (isInfoEnabled) notifyInfo(format(template, arg1), OptionVal.Some(marker))
  }

  override def info(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = {
    if (isInfoEnabled) notifyInfo(format(template, arg1, arg2), OptionVal.Some(marker))
  }

  override def info(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3), OptionVal.Some(marker))
  }

  override def info(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isInfoEnabled) notifyInfo(format(template, arg1, arg2, arg3, arg4), OptionVal.Some(marker))
  }

  override def debug(message: String): Unit = {
    if (isDebugEnabled) notifyDebug(message, OptionVal.None)
  }

  override def debug(template: String, arg1: Any): Unit = {
    if (isDebugEnabled) notifyDebug(format(template, arg1), OptionVal.None)
  }

  override def debug(template: String, arg1: Any, arg2: Any): Unit = {
    if (isDebugEnabled) notifyDebug(format(template, arg1, arg2), OptionVal.None)
  }

  override def debug(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3), OptionVal.None)
  }

  override def debug(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3, arg4), OptionVal.None)
  }

  override def debug(marker: LogMarker, message: String): Unit = {
    if (isDebugEnabled) notifyDebug(message, OptionVal.Some(marker))
  }

  override def debug(marker: LogMarker, template: String, arg1: Any): Unit = {
    if (isDebugEnabled) notifyDebug(format(template, arg1), OptionVal.Some(marker))
  }

  override def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = {
    if (isDebugEnabled) notifyDebug(format(template, arg1, arg2), OptionVal.Some(marker))
  }

  override def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3), OptionVal.Some(marker))
  }

  override def debug(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isDebugEnabled) notifyDebug(format(template, arg1, arg2, arg3, arg4), OptionVal.Some(marker))
  }

  override def log(level: LogLevel, message: String): Unit = {
    if (isLevelEnabled(level)) notify(level, message, OptionVal.None)
  }

  override def log(level: LogLevel, template: String, arg1: Any): Unit = {
    if (isLevelEnabled(level)) notify(level, format(template, arg1), OptionVal.None)
  }

  override def log(level: LogLevel, template: String, arg1: Any, arg2: Any): Unit = {
    if (isLevelEnabled(level)) notify(level, format(template, arg1, arg2), OptionVal.None)
  }

  override def log(level: LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isLevelEnabled(level)) notify(level, format(template, arg1, arg2, arg3), OptionVal.None)
  }

  override def log(level: LogLevel, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isLevelEnabled(level)) notify(level, format(template, arg1, arg2, arg3, arg4), OptionVal.None)
  }

  override def log(level: LogLevel, marker: LogMarker, message: String): Unit = {
    if (isLevelEnabled(level)) notify(level, message, OptionVal.Some(marker))
  }

  override def log(level: LogLevel, marker: LogMarker, template: String, arg1: Any): Unit = {
    if (isLevelEnabled(level)) notify(level, format(template, arg1), OptionVal.Some(marker))
  }

  override def log(level: LogLevel, marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = {
    if (isLevelEnabled(level)) notify(level, format(template, arg1, arg2), OptionVal.Some(marker))
  }

  override def log(level: LogLevel, marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isLevelEnabled(level)) notify(level, format(template, arg1, arg2, arg3), OptionVal.Some(marker))
  }

  override def log(
      level: LogLevel,
      marker: LogMarker,
      template: String,
      arg1: Any,
      arg2: Any,
      arg3: Any,
      arg4: Any): Unit = {
    if (isLevelEnabled(level)) notify(level, format(template, arg1, arg2, arg3, arg4), OptionVal.Some(marker))
  }

  protected def notify(level: LogLevel, message: String, marker: OptionVal[LogMarker]): Unit = level match {
    case ErrorLevel   => notifyDebug(message, marker)
    case WarningLevel => notifyWarning(message, OptionVal.None, marker)
    case InfoLevel    => notifyInfo(message, marker)
    case DebugLevel   => notifyDebug(message, marker)
    case _            => ()
  }

  /**
   * If `arg` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   */
  private def format(t: String, arg1: Any): String = arg1 match {
    case a: Array[_] if !a.getClass.getComponentType.isPrimitive => formatArrayImpl(t, a.toSeq)
    case a: Array[_]                                             => formatArrayImpl(t, a.map(_.asInstanceOf[AnyRef]).toSeq)
    case x                                                       => formatArray(t, x)
  }
  private def format(t: String, arg1: Any, arg2: Any): String = formatArray(t, arg1, arg2)
  private def format(t: String, arg1: Any, arg2: Any, arg3: Any): String = formatArray(t, arg1, arg2, arg3)
  private def format(t: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): String =
    formatArray(t, arg1, arg2, arg3, arg4)

  private def formatArray(t: String, arg: Any*): String =
    formatArrayImpl(t, arg)

  private def formatArrayImpl(t: String, arg: Seq[Any]): String = {
    val sb = new java.lang.StringBuilder(64)
    var p = 0
    var startIndex = 0
    while (p < arg.length) {
      val index = t.indexOf("{}", startIndex)
      if (index == -1) {
        sb.append(t.substring(startIndex, t.length)).append(" WARNING arguments left: ").append(arg.length - p)
        p = arg.length
        startIndex = t.length
      } else {
        sb.append(t.substring(startIndex, index)).append(arg(p))
        startIndex = index + 2
        p += 1
      }
    }
    sb.append(t.substring(startIndex, t.length)).toString
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class LoggerAdapterImpl(
    bus: LoggingBus,
    logClass: Class[_],
    logSource: String,
    loggingFilter: LoggingFilterWithMarker)
    extends AbstractLogger {

  override def isErrorEnabled = loggingFilter.isErrorEnabled(logClass, logSource)
  override def isWarningEnabled = loggingFilter.isWarningEnabled(logClass, logSource)
  override def isInfoEnabled = loggingFilter.isInfoEnabled(logClass, logSource)
  override def isDebugEnabled = loggingFilter.isDebugEnabled(logClass, logSource)

  override def isErrorEnabled(marker: LogMarker): Boolean =
    loggingFilter.isErrorEnabled(logClass, logSource, marker.asInstanceOf[UntypedLM])
  override def isWarningEnabled(marker: LogMarker): Boolean =
    loggingFilter.isWarningEnabled(logClass, logSource, marker.asInstanceOf[UntypedLM])
  override def isInfoEnabled(marker: LogMarker): Boolean =
    loggingFilter.isInfoEnabled(logClass, logSource, marker.asInstanceOf[UntypedLM])
  override def isDebugEnabled(marker: LogMarker): Boolean =
    loggingFilter.isDebugEnabled(logClass, logSource, marker.asInstanceOf[UntypedLM])

  override def withMdc(mdc: Map[String, Any]): Logger = {
    val mdcAdapter = new LoggerAdapterImpl(bus, logClass, logSource, loggingFilter)
    mdcAdapter.mdc = mdc
    mdcAdapter
  }

  def withLoggerClass(clazz: Class[_]): Logger = {
    val withClass = new LoggerAdapterImpl(bus, clazz, logSource, loggingFilter)
    withClass.mdc = mdc
    withClass
  }

  def withLogSource(logSource: String): Logger = {
    val withSource = new LoggerAdapterImpl(bus, logClass, logSource, loggingFilter)
    withSource.mdc = mdc
    withSource
  }

  private[akka] def notifyError(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit = {
    val error = cause match {
      case OptionVal.Some(cause) =>
        marker match {
          case OptionVal.Some(m) => Error(cause, logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
          case OptionVal.None    => Error(cause, logSource, logClass, message, mdc)
        }
      case OptionVal.None =>
        marker match {
          case OptionVal.Some(m) => Error(logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
          case OptionVal.None    => Error(logSource, logClass, message, mdc)
        }
    }
    bus.publish(error)
  }

  private[akka] def notifyWarning(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit = {
    val warning =
      if (cause.isDefined) Warning(cause.get, logSource, logClass, message, mdc, marker.orNull.asInstanceOf[UntypedLM])
      else
        marker match {
          case OptionVal.Some(m) => Warning(logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
          case OptionVal.None    => Warning(logSource, logClass, message, mdc)
        }
    bus.publish(warning)
  }

  private[akka] def notifyInfo(message: String, marker: OptionVal[LogMarker]): Unit = {
    val info = marker match {
      case OptionVal.Some(m) => Info(logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
      case OptionVal.None    => Info(logSource, logClass, message, mdc)
    }
    bus.publish(info)
  }

  private[akka] def notifyDebug(message: String, marker: OptionVal[LogMarker]): Unit = {
    val debug = marker match {
      case OptionVal.Some(m) => Debug(logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
      case OptionVal.None    => Debug(logSource, logClass, message, mdc)
    }
    bus.publish(debug)
  }

}
