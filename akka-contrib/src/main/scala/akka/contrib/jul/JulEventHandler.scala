package akka.contrib.jul

import akka.event.Logging._
import akka.actor._
import akka.event.LoggingAdapter
import java.util.logging
import concurrent.{ ExecutionContext, Future }

/**
 * Makes the Akka `Logging` API available as the `log`
 * field, using `java.util.logging` as the backend.
 *
 * This trait does not require an `ActorSystem` and is
 * encouraged to be used as a general purpose Scala
 * logging API.
 *
 * For `Actor`s, use `ActorLogging` instead.
 *
 * @author Sam Halliday
 */
trait JavaLogging {

  @transient
  protected lazy val log = new JavaLoggingAdapter {
    def logger = logging.Logger.getLogger(JavaLogging.this.getClass.getName)
  }
}

/**
 * `java.util.logging` EventHandler.
 *
 * @author Sam Halliday
 */
class JavaLoggingEventHandler extends Actor {

  def receive = {
    case event @ Error(cause, logSource, logClass, message) ⇒
      log(logging.Level.SEVERE, cause, logSource, logClass, message, event)

    case event @ Warning(logSource, logClass, message) ⇒
      log(logging.Level.WARNING, null, logSource, logClass, message, event)

    case event @ Info(logSource, logClass, message) ⇒
      log(logging.Level.INFO, null, logSource, logClass, message, event)

    case event @ Debug(logSource, logClass, message) ⇒
      log(logging.Level.CONFIG, null, logSource, logClass, message, event)

    case InitializeLogger(_) ⇒
      sender ! LoggerInitialized
  }

  @inline
  def log(level: logging.Level, cause: Throwable, logSource: String, logClass: Class[_], message: Any, event: LogEvent) {
    val logger = logging.Logger.getLogger(logSource)
    val record = new logging.LogRecord(level, message.toString)
    record.setLoggerName(logger.getName)
    record.setThrown(cause)
    record.setThreadID(event.thread.getId.toInt)
    record.setSourceClassName(logClass.getName)
    record.setSourceMethodName(null) // lost forever
    logger.log(record)
  }
}

trait JavaLoggingAdapter extends LoggingAdapter {

  def logger: logging.Logger

  /** Override-able option for asynchronous logging */
  def loggingExecutionContext: Option[ExecutionContext] = None

  def isErrorEnabled = logger.isLoggable(logging.Level.SEVERE)

  def isWarningEnabled = logger.isLoggable(logging.Level.WARNING)

  def isInfoEnabled = logger.isLoggable(logging.Level.INFO)

  def isDebugEnabled = logger.isLoggable(logging.Level.CONFIG)

  protected def notifyError(message: String) {
    log(logging.Level.SEVERE, null, message)
  }

  protected def notifyError(cause: Throwable, message: String) {
    log(logging.Level.SEVERE, cause, message)
  }

  protected def notifyWarning(message: String) {
    log(logging.Level.WARNING, null, message)
  }

  protected def notifyInfo(message: String) {
    log(logging.Level.INFO, null, message)
  }

  protected def notifyDebug(message: String) {
    log(logging.Level.CONFIG, null, message)
  }

  @inline
  def log(level: logging.Level, cause: Throwable, message: String) {
    val record = new logging.LogRecord(level, message.toString)
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
  private def updateSource(record: logging.LogRecord) {
    val throwable = new Throwable()
    val stack = throwable.getStackTrace
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