/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event.slf4j

import org.slf4j.{ Logger ⇒ SLFLogger, LoggerFactory ⇒ SLFLoggerFactory }
import org.slf4j.MDC
import akka.event.Logging._
import akka.actor._
import akka.event.DummyClassForStringSources

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
 * SLF4J Event Handler.
 *
 * The thread in which the logging was performed is captured in
 * Mapped Diagnostic Context (MDC) with attribute name "sourceThread".
 */
class Slf4jEventHandler extends Actor with SLF4JLogging {

  val mdcThreadAttributeName = "sourceThread"
  val mdcAkkaSourceAttributeName = "akkaSource"

  def receive = {

    case event @ Error(cause, logSource, logClass, message) ⇒
      withMdc(logSource, event.thread.getName) {
        cause match {
          case Error.NoCause | null ⇒ Logger(logClass, logSource).error(if (message != null) message.toString else null)
          case cause                ⇒ Logger(logClass, logSource).error(if (message != null) message.toString else cause.getLocalizedMessage, cause)
        }
      }

    case event @ Warning(logSource, logClass, message) ⇒
      withMdc(logSource, event.thread.getName) { Logger(logClass, logSource).warn("{}", message.asInstanceOf[AnyRef]) }

    case event @ Info(logSource, logClass, message) ⇒
      withMdc(logSource, event.thread.getName) { Logger(logClass, logSource).info("{}", message.asInstanceOf[AnyRef]) }

    case event @ Debug(logSource, logClass, message) ⇒
      withMdc(logSource, event.thread.getName) { Logger(logClass, logSource).debug("{}", message.asInstanceOf[AnyRef]) }

    case InitializeLogger(_) ⇒
      log.info("Slf4jEventHandler started")
      sender ! LoggerInitialized
  }

  @inline
  final def withMdc(logSource: String, thread: String)(logStatement: ⇒ Unit) {
    MDC.put(mdcAkkaSourceAttributeName, logSource)
    MDC.put(mdcThreadAttributeName, thread)
    try {
      logStatement
    } finally {
      MDC.remove(mdcAkkaSourceAttributeName)
      MDC.remove(mdcThreadAttributeName)
    }
  }

}

