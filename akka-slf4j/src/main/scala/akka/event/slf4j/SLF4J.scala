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

object Logger {
  def apply(logger: String): SLFLogger = SLFLoggerFactory getLogger logger
  def apply(logClass: Class[_], logSource: String): SLFLogger = logClass match {
    case c if c == classOf[DummyClassForStringSources] ⇒ apply(logSource)
    case _ ⇒ SLFLoggerFactory getLogger logClass
  }
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
          case Error.NoCause ⇒ Logger(logClass, logSource).error(message.toString)
          case _             ⇒ Logger(logClass, logSource).error(message.toString, cause)
        }
      }

    case event @ Warning(logSource, logClass, message) ⇒
      withMdc(logSource, event.thread.getName) {
        Logger(logClass, logSource).warn("{}", message.asInstanceOf[AnyRef])
      }

    case event @ Info(logSource, logClass, message) ⇒
      withMdc(logSource, event.thread.getName) {
        Logger(logClass, logSource).info("{}", message.asInstanceOf[AnyRef])
      }

    case event @ Debug(logSource, logClass, message) ⇒
      withMdc(logSource, event.thread.getName) {
        Logger(logClass, logSource).debug("{}", message.asInstanceOf[AnyRef])
      }

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

