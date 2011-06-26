/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.event.slf4j

import org.slf4j.{ Logger ⇒ SLFLogger, LoggerFactory ⇒ SLFLoggerFactory }

import akka.event.EventHandler
import akka.actor._
import Actor._

/**
 * Base trait for all classes that wants to be able use the SLF4J logging infrastructure.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Logging {
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
class Slf4jEventHandler extends Actor with Logging {
  import EventHandler._

  self.id = ID
  self.dispatcher = EventHandlerDispatcher

  def receive = {
    case Error(cause, instance, message) ⇒
      log.error("\n\t[{}]\n\t[{}]\n\t[{}]",
        Array[AnyRef](instance.getClass.getName, message.asInstanceOf[AnyRef], stackTraceFor(cause)))

    case Warning(instance, message) ⇒
      log.warn("\n\t[{}]\n\t[{}]", instance.getClass.getName, message)

    case Info(instance, message) ⇒
      log.info("\n\t[{}]\n\t[{}]", instance.getClass.getName, message)

    case Debug(instance, message) ⇒
      log.debug("\n\t[{}]\n\t[{}]", instance.getClass.getName, message)

    case event ⇒ log.debug("\n\t[{}]", event.toString)
  }
}

