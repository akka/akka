package akka.contrib.jul

import akka.event.Logging._
import akka.actor._
import akka.event.LoggingAdapter
import java.util.logging
import sun.misc.SharedSecrets
import concurrent.{ExecutionContext, Future}

/** Mix in `ActorPathLogging` to your `Actor` to easily obtain a reference
  * to a logger, which registers the context path as the message source.
  */
trait ActorPathLogging {
  this: Actor =>

  val log = akka.event.Logging(context.system, self.path.name)
}

/** Makes `java.util.logging` available as a `logger` field
  * and provides convenience logging methods that agree exactly
  * with the `akka.event.Logging` API.
  *
  * Calling class and
  *
  * WARNING: Not suitable for use from an `Actor`. Instead, mixin
  * `ActorLogging` or create an instance of `akka.event.Logging`.
  *
  * @author Sam Halliday
  */
trait JavaLogging extends LoggingAdapter {

  @transient
  protected lazy val logger = logging.Logger.getLogger(toScalaClassName(getClass.getName))

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
        case thrown: Throwable => thrown.printStackTrace()
      }
    }
    else
      logger.log(record)
  }

  // it is unfortunate that this workaround is needed
  private def updateSource(record: logging.LogRecord) {
    // code duplication with LogRecord
    val access = SharedSecrets.getJavaLangAccess
    val throwable = new Throwable()
    val depth = access.getStackTraceDepth(throwable)
    var i = 0
    while (i < depth) {
      val frame = access.getStackTraceElement(throwable, i)
      val cname = frame.getClassName
      if (!cname.startsWith("java.lang.reflect.") &&
        !cname.startsWith("sun.reflect.") &&
        cname != javaLoggerTraitName) {
        record.setSourceClassName(toScalaClassName(cname))
        record.setSourceMethodName(frame.getMethodName)
        return
      }
      i += 1
    }
  }

  // can this be set automatically?
  private final val javaLoggerTraitName = "akka.contrib.jul.JavaLogging$class"

  @inline
  private final def toScalaClassName(cname: String) =
    if (cname.endsWith("$"))
      cname.substring(0, cname.length - 1)
    else cname
}

/** `java.util.logging` EventHandler.
  *
  * @author Sam Halliday
  */
class JavaLoggingEventHandler extends Actor with JavaLogging {

  def receive = {
    case event@Error(cause, logSource, logClass, message) ⇒
      log(logging.Level.SEVERE, cause, logSource, logClass, message, event)

    case event@Warning(logSource, logClass, message) ⇒
      log(logging.Level.WARNING, null, logSource, logClass, message, event)

    case event@Info(logSource, logClass, message) ⇒
      log(logging.Level.INFO, null, logSource, logClass, message, event)

    case event@Debug(logSource, logClass, message) ⇒
      log(logging.Level.CONFIG, null, logSource, logClass, message, event)

    case InitializeLogger(_) ⇒
      logger.config("starting")
      sender ! LoggerInitialized
  }

  @inline
  def log(level: logging.Level, cause: Throwable, logSource: String, logClass: Class[_], message: Any, event: LogEvent) {
    val sourceLogger = logging.Logger.getLogger(logSource)
    val record = new logging.LogRecord(level, message.toString)
    record.setLoggerName(sourceLogger.getName)
    record.setThrown(cause)
    record.setThreadID(event.thread.getId.toInt)
    record.setSourceClassName(logClass.getName)
    record.setSourceMethodName("<unknown>") // lost forever
    sourceLogger.log(record)
  }
}

