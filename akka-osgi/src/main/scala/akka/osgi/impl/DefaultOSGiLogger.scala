/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi.impl

import akka.event.Logging.{ LogEvent, DefaultLogger }
import org.osgi.service.log.LogService
import akka.event.Logging
import collection.mutable.Buffer

/**
 * EventHandler for OSGi environment.
 * Stands for an interface between akka and the OSGi LogService
 * It uses the OSGi LogService to log the received LogEvents
 */
class DefaultOSGiLogger extends DefaultLogger {

  //the Default Logger needs to be aware of the LogService which is published on the EventStream
  context.system.eventStream.subscribe(self, classOf[LogService])

  val messageFormat = "[%s] %s"

  var messagesToLog: Vector[LogEvent] = Vector()

  override def receive: Receive = {
    //register the log service to use
    case logService: LogService ⇒ setLogService(logService)
    case logEvent: LogEvent     ⇒ messagesToLog = messagesToLog :+ logEvent
  }

  def setLogService(logService: LogService) {
      messagesToLog.foreach(logMessage(logService, _))
      context.become(receiveEvent(logService))
  }

  def receiveEvent(logService: LogService): Receive = { case logEvent: LogEvent ⇒ logMessage(logService, logEvent) }

  def logMessage(logService: LogService, event: LogEvent) {
    event match {
      case error: Logging.Error ⇒ logService.log(event.level.asInt, Logging.stackTraceFor(error.cause))
      case _ =>  logService.log(event.level.asInt, messageFormat.format(event.logSource, event.message))
    }
  }

}