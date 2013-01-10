package akka.osgi.impl

import akka.event.Logging.{LogEvent, DefaultLogger}
import org.osgi.service.log.LogService
import akka.event.Logging

/**
 * EventHandler for OSGi environment.
 * Stands for an interface between akka and the OSGi LogService
 * It uses the OSGi LogService to log the received LogEvents
 */
class DefaultOSGiLogger extends DefaultLogger {

  //the Default Logger needs to be aware of the LogService which is published on the EventStream
  context.system.eventStream.subscribe(self, classOf[LogService])

  val messageFormat = "[%s] %s".intern

  var logService: Option[LogService] = None
  var messagesToLog: List[LogEvent] = List()

  def receiveEvent: Receive = {
    //register the log service to use
    case logService: LogService => setLogService(logService)
    case logEvent: LogEvent => if (logService.isEmpty) {
      messagesToLog ::= logEvent
    } else {
      logMessage(logEvent)
    }
  }

  def setLogService(logService: LogService) {
    if (this.logService.isEmpty) {
      this.logService = Some(logService)
      messagesToLog.foreach(logMessage(_))
      messagesToLog = List()
    }
  }

  override def receive() = receiveEvent orElse super.receive

  def logMessage(event: LogEvent) {
    logService.get.log(event.level.asInt, messageFormat.format(event.logSource, event.message))
    event match {
      case error: Logging.Error => logService.get.log(event.level.asInt, Logging.stackTraceFor(error.cause))
      case x =>
    }
  }
}