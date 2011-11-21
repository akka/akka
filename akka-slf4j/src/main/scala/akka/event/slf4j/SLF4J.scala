/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event.slf4j

import org.slf4j.{ Logger ⇒ SLFLogger, LoggerFactory ⇒ SLFLoggerFactory }

import akka.event.Logging._
import akka.actor._

/**
 * Base trait for all classes that wants to be able use the SLF4J logging infrastructure.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
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
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Slf4jEventHandler extends Actor with SLF4JLogging {

  def receive = {
    case event @ Error(cause, logSource, message) ⇒
      Logger(logSource).error("[{}] [{}] [{}]",
        Array[AnyRef](event.thread.getName, message.asInstanceOf[AnyRef], stackTraceFor(cause)))

    case event @ Warning(logSource, message) ⇒
      Logger(logSource).warn("[{}] [{}]",
        event.thread.getName, message.asInstanceOf[AnyRef])

    case event @ Info(logSource, message) ⇒
      Logger(logSource).info("[{}] [{}]",
        event.thread.getName, message.asInstanceOf[AnyRef])

    case event @ Debug(logSource, message) ⇒
      Logger(logSource).debug("[{}] [{}]",
        event.thread.getName, message.asInstanceOf[AnyRef])

    case InitializeLogger(_) ⇒
      log.info("Slf4jEventHandler started")
      sender ! LoggerInitialized
  }

}

