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
  def apply(clazz: Class[_]): SLFLogger = apply(clazz.getName)
  def root: SLFLogger = apply(SLFLogger.ROOT_LOGGER_NAME)
}

/**
 * SLF4J Event Handler.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Slf4jEventHandler extends Actor with SLF4JLogging {

  def receive = {
    case event @ Error(cause, instance, message) ⇒
      logger(instance).error("[{}] [{}] [{}]",
        Array[AnyRef](event.thread.getName, message.asInstanceOf[AnyRef], stackTraceFor(cause)))

    case event @ Warning(instance, message) ⇒
      logger(instance).warn("[{}] [{}]",
        event.thread.getName, message.asInstanceOf[AnyRef])

    case event @ Info(instance, message) ⇒
      logger(instance).info("[{}] [{}]",
        event.thread.getName, message.asInstanceOf[AnyRef])

    case event @ Debug(instance, message) ⇒
      logger(instance).debug("[{}] [{}]",
        event.thread.getName, message.asInstanceOf[AnyRef])

    case InitializeLogger(_) ⇒ log.info("Slf4jEventHandler started")
  }

  def logger(instance: AnyRef): SLFLogger = instance match {
    // TODO make sure that this makes sense (i.e. should be the full path after Peter’s changes)
    case a: ActorRef ⇒ Logger(a.address)
    case _           ⇒ Logger(instance.getClass)
  }
}

