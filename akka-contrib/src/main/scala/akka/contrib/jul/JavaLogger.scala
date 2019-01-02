/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.jul

import akka.event.Logging._
import akka.actor._
import akka.event.LoggingAdapter
import java.util.logging
import scala.concurrent.{ ExecutionContext, Future }
import akka.dispatch.RequiresMessageQueue
import akka.event.LoggerMessageQueueSemantics

/**
 * Makes the Akka `Logging` API available as the `log`
 * field, using `java.util.logging` as the backend.
 *
 * This trait does not require an `ActorSystem` and is
 * encouraged to be used as a general purpose Scala
 * logging API.
 *
 * For `Actor`s, use `ActorLogging` instead.
 */
@deprecated("Feel free to copy", "2.5.0")
trait JavaLogging {

  @transient
  protected lazy val log = new JavaLoggingAdapter {
    def logger = logging.Logger.getLogger(JavaLogging.this.getClass.getName)
  }
}

/**
 * `java.util.logging` logger.
 */
@deprecated("Use akka.event.jul.JavaLogger in akka-actor instead", "2.5.0")
class JavaLogger extends Actor with RequiresMessageQueue[LoggerMessageQueueSemantics] {

  def receive = {
    case event @ Error(cause, _, _, _) ⇒ log(logging.Level.SEVERE, cause, event)
    case event: Warning                ⇒ log(logging.Level.WARNING, null, event)
    case event: Info                   ⇒ log(logging.Level.INFO, null, event)
    case event: Debug                  ⇒ log(logging.Level.CONFIG, null, event)
    case InitializeLogger(_)           ⇒ sender() ! LoggerInitialized
  }

  @inline
  def log(level: logging.Level, cause: Throwable, event: LogEvent): Unit = {
    val logger = logging.Logger.getLogger(event.logSource)
    val record = new logging.LogRecord(level, String.valueOf(event.message))
    record.setLoggerName(logger.getName)
    record.setThrown(cause)
    record.setThreadID(event.thread.getId.toInt)
    record.setSourceClassName(event.logClass.getName)
    record.setSourceMethodName(null) // lost forever
    logger.log(record)
  }
}

@deprecated("Feel free to copy", "2.5.0")
trait JavaLoggingAdapter extends LoggingAdapter {

  def logger: logging.Logger

  /** Override-able option for asynchronous logging */
  def loggingExecutionContext: Option[ExecutionContext] = None

  def isErrorEnabled = logger.isLoggable(logging.Level.SEVERE)

  def isWarningEnabled = logger.isLoggable(logging.Level.WARNING)

  def isInfoEnabled = logger.isLoggable(logging.Level.INFO)

  def isDebugEnabled = logger.isLoggable(logging.Level.CONFIG)

  protected def notifyError(message: String): Unit =
    log(logging.Level.SEVERE, null, message)

  protected def notifyError(cause: Throwable, message: String): Unit =
    log(logging.Level.SEVERE, cause, message)

  protected def notifyWarning(message: String): Unit =
    log(logging.Level.WARNING, null, message)

  protected def notifyInfo(message: String): Unit =
    log(logging.Level.INFO, null, message)

  protected def notifyDebug(message: String): Unit =
    log(logging.Level.CONFIG, null, message)

  @inline
  def log(level: logging.Level, cause: Throwable, message: String): Unit = {
    val record = new logging.LogRecord(level, message)
    record.setLoggerName(logger.getName)
    record.setThrown(cause)
    updateSource(record)

    if (loggingExecutionContext.isDefined) {
      implicit val context = loggingExecutionContext.get
      Future(logger.log(record)).onFailure {
        case thrown: Throwable ⇒ thrown.printStackTrace()
      }
    } else
      logger.log(record)
  }

  // it is unfortunate that this workaround is needed
  private def updateSource(record: logging.LogRecord): Unit = {
    val stack = Thread.currentThread.getStackTrace
    val source = stack.find {
      frame ⇒
        val cname = frame.getClassName
        !cname.startsWith("akka.contrib.jul.") &&
          !cname.startsWith("akka.event.LoggingAdapter") &&
          !cname.startsWith("java.lang.reflect.") &&
          !cname.startsWith("sun.reflect.")
    }
    if (source.isDefined) {
      record.setSourceClassName(source.get.getClassName)
      record.setSourceMethodName(source.get.getMethodName)
    } else {
      record.setSourceClassName(null)
      record.setSourceMethodName(null)
    }
  }

}
