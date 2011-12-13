/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event.slf4j

import org.slf4j.{ Logger ⇒ SLFLogger, LoggerFactory ⇒ SLFLoggerFactory }
import org.slf4j.MDC
import akka.event.Logging._
import akka.actor._

/**
 * Base trait for all classes that wants to be able use the SLF4J logging infrastructure.
 */
trait SLF4JLogging {
  @transient
  lazy val log = Logger(this.getClass.getName)
}

object Logger {
  def apply(logger: String): SLFLogger = SLFLoggerFactory getLogger logger
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

  def receive = {

    case event @ Error(cause, logSource, message) ⇒
      withMdc(mdcThreadAttributeName, event.thread.getName) {
        cause match {
          case Error.NoCause ⇒ Logger(logSource).error(message.toString)
          case _             ⇒ Logger(logSource).error(message.toString, cause)
        }
      }

    case event @ Warning(logSource, message) ⇒
      withMdc(mdcThreadAttributeName, event.thread.getName) {
        Logger(logSource).warn("{}", message.asInstanceOf[AnyRef])
      }

    case event @ Info(logSource, message) ⇒
      withMdc(mdcThreadAttributeName, event.thread.getName) {
        Logger(logSource).info("{}", message.asInstanceOf[AnyRef])
      }

    case event @ Debug(logSource, message) ⇒
      withMdc(mdcThreadAttributeName, event.thread.getName) {
        Logger(logSource).debug("{}", message.asInstanceOf[AnyRef])
      }

    case InitializeLogger(_) ⇒
      log.info("Slf4jEventHandler started")
      sender ! LoggerInitialized
  }

  @inline
  final def withMdc(name: String, value: String)(logStatement: ⇒ Unit) {
    MDC.put(name, value)
    try {
      logStatement
    } finally {
      MDC.remove(name)
    }
  }

}

