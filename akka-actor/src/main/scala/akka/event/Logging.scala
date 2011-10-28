/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event
import akka.actor.Actor

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

trait ActorLogging extends Logging { this: Actor ⇒

  import EventHandler._

  def isErrorEnabled = app.eventHandler.level >= ErrorLevel
  def isWarningEnabled = app.eventHandler.level >= WarningLevel
  def isInfoEnabled = app.eventHandler.level >= InfoLevel
  def isDebugEnabled = app.eventHandler.level >= DebugLevel

  protected def notifyError(cause: Throwable, message: String) { app.eventHandler.notifyListeners(Error(cause, context.self, message)) }

  protected def notifyWarning(message: String) { app.eventHandler.notifyListeners(Warning(context.self, message)) }

  protected def notifyInfo(message: String) { app.eventHandler.notifyListeners(Info(context.self, message)) }

  protected def notifyDebug(message: String) { app.eventHandler.notifyListeners(Debug(context.self, message)) }

}

class EventHandlerLogging(val eventHandler: EventHandler, val loggingInstance: AnyRef) extends Logging {

  import EventHandler._

  def isErrorEnabled = eventHandler.level >= ErrorLevel
  def isWarningEnabled = eventHandler.level >= WarningLevel
  def isInfoEnabled = eventHandler.level >= InfoLevel
  def isDebugEnabled = eventHandler.level >= DebugLevel

  protected def notifyError(cause: Throwable, message: String) { eventHandler.notifyListeners(Error(cause, loggingInstance, message)) }

  protected def notifyWarning(message: String) { eventHandler.notifyListeners(Warning(loggingInstance, message)) }

  protected def notifyInfo(message: String) { eventHandler.notifyListeners(Info(loggingInstance, message)) }

  protected def notifyDebug(message: String) { eventHandler.notifyListeners(Debug(loggingInstance, message)) }

}
