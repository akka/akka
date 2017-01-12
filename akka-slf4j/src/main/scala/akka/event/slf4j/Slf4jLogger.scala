/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.event.slf4j

import org.slf4j.{ MDC, Marker, MarkerFactory, Logger ⇒ SLFLogger, LoggerFactory ⇒ SLFLoggerFactory }
import akka.event.Logging._
import akka.actor._
import akka.event.DummyClassForStringSources
import akka.util.Helpers
import akka.event.LoggingFilter
import akka.event.EventStream
import akka.dispatch.RequiresMessageQueue
import akka.event.LoggerMessageQueueSemantics

/**
 * Base trait for all classes that wants to be able use the SLF4J logging infrastructure.
 */
trait SLF4JLogging {
  @transient
  lazy val log = Logger(this.getClass.getName)
}

/**
 * Logger is a factory for obtaining SLF4J-Loggers
 */
object Logger {
  /**
   * @param logger - which logger
   * @return a Logger that corresponds for the given logger name
   */
  def apply(logger: String): SLFLogger = SLFLoggerFactory getLogger logger

  /**
   * @param logClass - the class to log for
   * @param logSource - the textual representation of the source of this log stream
   * @return a Logger for the specified parameters
   */
  def apply(logClass: Class[_], logSource: String): SLFLogger = logClass match {
    case c if c == classOf[DummyClassForStringSources] ⇒ apply(logSource)
    case _ ⇒ SLFLoggerFactory getLogger logClass
  }

  /**
   * Returns the SLF4J Root Logger
   */
  def root: SLFLogger = apply(SLFLogger.ROOT_LOGGER_NAME)
}

/**
 * SLF4J logger.
 *
 * The thread in which the logging was performed is captured in
 * Mapped Diagnostic Context (MDC) with attribute name "sourceThread".
 */
class Slf4jLogger extends Actor with SLF4JLogging with RequiresMessageQueue[LoggerMessageQueueSemantics] {

  val mdcThreadAttributeName = "sourceThread"
  val mdcActorSystemAttributeName = "sourceActorSystem"
  val mdcAkkaSourceAttributeName = "akkaSource"
  val mdcAkkaTimestamp = "akkaTimestamp"

  def receive = {

    case event @ Error(cause, logSource, logClass, message) ⇒
      withMdc(logSource, event) {
        cause match {
          case Error.NoCause | null ⇒
            Logger(logClass, logSource).error(markerIfPresent(event), if (message != null) message.toString else null)
          case _ ⇒
            Logger(logClass, logSource).error(markerIfPresent(event), if (message != null) message.toString else cause.getLocalizedMessage, cause)
        }
      }

    case event @ Warning(logSource, logClass, message) ⇒
      withMdc(logSource, event) {
        Logger(logClass, logSource).warn(markerIfPresent(event), "{}", message.asInstanceOf[AnyRef])
      }

    case event @ Info(logSource, logClass, message) ⇒
      withMdc(logSource, event) {
        Logger(logClass, logSource).info(markerIfPresent(event), "{}", message.asInstanceOf[AnyRef])
      }

    case event @ Debug(logSource, logClass, message) ⇒
      withMdc(logSource, event) {
        Logger(logClass, logSource).debug(markerIfPresent(event), "{}", message.asInstanceOf[AnyRef])
      }

    case InitializeLogger(_) ⇒
      log.info("Slf4jLogger started")
      sender() ! LoggerInitialized
  }

  @inline
  final def withMdc(logSource: String, logEvent: LogEvent)(logStatement: ⇒ Unit) {
    MDC.put(mdcAkkaSourceAttributeName, logSource)
    MDC.put(mdcThreadAttributeName, logEvent.thread.getName)
    MDC.put(mdcAkkaTimestamp, formatTimestamp(logEvent.timestamp))
    MDC.put(mdcActorSystemAttributeName, actorSystemName)
    logEvent.mdc foreach { case (k, v) ⇒ MDC.put(k, String.valueOf(v)) }
    try logStatement finally {
      MDC.remove(mdcAkkaSourceAttributeName)
      MDC.remove(mdcThreadAttributeName)
      MDC.remove(mdcAkkaTimestamp)
      MDC.remove(mdcActorSystemAttributeName)
      logEvent.mdc.keys.foreach(k ⇒ MDC.remove(k))
    }
  }

  private final def markerIfPresent(event: LogEvent): Marker =
    event match {
      case m: LogEventWithMarker ⇒ MarkerFactory.getMarker(m.marker.name)
      case _                     ⇒ null
    }

  /**
   * Override this method to provide a differently formatted timestamp
   * @param timestamp a "currentTimeMillis"-obtained timestamp
   * @return the given timestamp as a UTC String
   */
  protected def formatTimestamp(timestamp: Long): String =
    Helpers.currentTimeMillisToUTCString(timestamp)

  private val actorSystemName = context.system.name
}

/**
 * [[akka.event.LoggingFilter]] that uses the log level defined in the SLF4J
 * backend configuration (e.g. logback.xml) to filter log events before publishing
 * the log events to the `eventStream`.
 */
class Slf4jLoggingFilter(settings: ActorSystem.Settings, eventStream: EventStream) extends LoggingFilter {
  def isErrorEnabled(logClass: Class[_], logSource: String) =
    (eventStream.logLevel >= ErrorLevel) && Logger(logClass, logSource).isErrorEnabled
  def isWarningEnabled(logClass: Class[_], logSource: String) =
    (eventStream.logLevel >= WarningLevel) && Logger(logClass, logSource).isWarnEnabled
  def isInfoEnabled(logClass: Class[_], logSource: String) =
    (eventStream.logLevel >= InfoLevel) && Logger(logClass, logSource).isInfoEnabled
  def isDebugEnabled(logClass: Class[_], logSource: String) =
    (eventStream.logLevel >= DebugLevel) && Logger(logClass, logSource).isDebugEnabled
}
