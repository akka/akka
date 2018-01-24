/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.actor.typed.{ Logger, LogMarker }
import akka.annotation.InternalApi
import akka.event.Logging.{ Debug, Error, Info, Warning }
import akka.event.{ LoggingBus, LoggingFilter, LogMarker ⇒ UntypedLM }
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class ActorLoggerImpl(bus: LoggingBus, logClass: Class[_], logSource: String, loggingFilter: LoggingFilter) extends Logger {

  private[akka] var mdc: Map[String, Any] = Map.empty

  override def isErrorEnabled = loggingFilter.isErrorEnabled(logClass, logSource)
  override def isWarningEnabled = loggingFilter.isWarningEnabled(logClass, logSource)
  override def isInfoEnabled = loggingFilter.isInfoEnabled(logClass, logSource)
  override def isDebugEnabled = loggingFilter.isDebugEnabled(logClass, logSource)

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

  override def error(marker: LogMarker, cause: Throwable, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isErrorEnabled) notifyError(format(template, arg1, arg2, arg3, arg4), OptionVal.Some(cause), OptionVal.Some(marker))
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
    if (isWarningEnabled) notifyWarning(message, OptionVal.None)
  }

  override def warning(template: String, arg1: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1), OptionVal.None)
  }

  override def warning(template: String, arg1: Any, arg2: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2), OptionVal.None)
  }

  override def warning(template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3), OptionVal.None)
  }

  override def warning(template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3, arg4), OptionVal.None)
  }

  override def warning(marker: LogMarker, message: String): Unit = {
    if (isWarningEnabled) notifyWarning(message, OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, template: String, arg1: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1), OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2), OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3), OptionVal.Some(marker))
  }

  override def warning(marker: LogMarker, template: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): Unit = {
    if (isWarningEnabled) notifyWarning(format(template, arg1, arg2, arg3, arg4), OptionVal.Some(marker))
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

  protected def notifyError(message: String, cause: OptionVal[Throwable], marker: OptionVal[LogMarker]): Unit = {
    val error = cause match {
      case OptionVal.Some(cause) ⇒
        marker match {
          case OptionVal.Some(m) ⇒ Error(cause, logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
          case OptionVal.None    ⇒ Error(cause, logSource, logClass, message, mdc)
        }
      case OptionVal.None ⇒
        marker match {
          case OptionVal.Some(m) ⇒ Error(logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
          case OptionVal.None    ⇒ Error(logSource, logClass, message, mdc)
        }
    }
    bus.publish(error)
  }

  protected def notifyWarning(message: String, marker: OptionVal[LogMarker]): Unit = {
    val warning = marker match {
      case OptionVal.Some(m) ⇒ Warning(logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
      case OptionVal.None    ⇒ Warning(logSource, logClass, message, mdc)
    }
    bus.publish(warning)
  }

  protected def notifyInfo(message: String, marker: OptionVal[LogMarker]): Unit = {
    val info = marker match {
      case OptionVal.Some(m) ⇒ Info(logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
      case OptionVal.None    ⇒ Info(logSource, logClass, message, mdc)
    }
    bus.publish(info)
  }

  protected def notifyDebug(message: String, marker: OptionVal[LogMarker]): Unit = {
    val debug = marker match {
      case OptionVal.Some(m) ⇒ Debug(logSource, logClass, message, mdc, m.asInstanceOf[UntypedLM])
      case OptionVal.None    ⇒ Debug(logSource, logClass, message, mdc)
    }
    bus.publish(debug)
  }

  /**
   * If `arg` is an `Array` it will be expanded into replacement arguments, which is useful when
   * there are more than four arguments.
   */
  private def format(t: String, arg1: Any): String = arg1 match {
    case a: Array[_] if !a.getClass.getComponentType.isPrimitive ⇒ formatArray(t, a: _*)
    case a: Array[_] ⇒ formatArray(t, (a map (_.asInstanceOf[AnyRef]): _*))
    case x ⇒ formatArray(t, x)
  }
  private def format(t: String, arg1: Any, arg2: Any): String = formatArray(t, arg1, arg2)
  private def format(t: String, arg1: Any, arg2: Any, arg3: Any): String = formatArray(t, arg1, arg2, arg3)
  private def format(t: String, arg1: Any, arg2: Any, arg3: Any, arg4: Any): String = formatArray(t, arg1, arg2, arg3, arg4)

  private def formatArray(t: String, arg: Any*): String = {
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
