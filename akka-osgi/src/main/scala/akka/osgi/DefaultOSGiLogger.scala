/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.osgi

import akka.event.Logging
import org.osgi.service.log.LogService
import akka.event.Logging.{ DefaultLogger, LogEvent }
import akka.event.Logging.Error.NoCause

/**
 * Logger for OSGi environment.
 * Stands for an interface between akka and the OSGi LogService
 * It uses the OSGi LogService to log the received LogEvents
 */
class DefaultOSGiLogger extends DefaultLogger {

  val messageFormat = " %s | %s | %s | %s"

  override def receive: Receive = uninitialisedReceive.orElse[Any, Unit](super.receive)

  /**
   * Behavior of the logger that waits for its LogService
   * @return  Receive: Store LogEvent or become initialised
   */
  def uninitialisedReceive: Receive = {
    var messagesToLog: Vector[LogEvent] = Vector()
    //the Default Logger needs to be aware of the LogService which is published on the EventStream
    context.system.eventStream.subscribe(self, classOf[LogService])
    context.system.eventStream.unsubscribe(self, UnregisteringLogService.getClass)

    /**
     * Logs every already received LogEvent and set the logger ready to log every incoming LogEvent.
     *
     * @param logService OSGi LogService that has been registered,
     */
    def setLogService(logService: LogService): Unit = {
      messagesToLog.foreach(x => {
        logMessage(logService, x)
      })
      context.become(initialisedReceive(logService))
    }

    {
      case logService: LogService => setLogService(logService)
      case logEvent: LogEvent     => messagesToLog :+= logEvent
    }
  }

  /**
   * Behavior of the Event handler that is setup (has received a LogService)
   * @param logService registered OSGi LogService
   * @return Receive : Logs LogEvent or go back to the uninitialised state
   */
  def initialisedReceive(logService: LogService): Receive = {
    context.system.eventStream.subscribe(self, UnregisteringLogService.getClass)
    context.system.eventStream.unsubscribe(self, classOf[LogService])

    {
      case logEvent: LogEvent      => logMessage(logService, logEvent)
      case UnregisteringLogService => context.become(uninitialisedReceive)
    }
  }

  /**
   * Logs a message in an OSGi LogService
   *
   * @param logService  OSGi LogService registered and used for logging
   * @param event akka LogEvent that is logged using the LogService
   */
  def logMessage(logService: LogService, event: LogEvent): Unit = {
    event match {
      case error: Logging.Error if error.cause != NoCause =>
        logService.log(
          event.level.asInt,
          messageFormat.format(timestamp(event), event.thread.getName, event.logSource, event.message),
          error.cause)
      case _ =>
        logService.log(
          event.level.asInt,
          messageFormat.format(timestamp(event), event.thread.getName, event.logSource, event.message))
    }
  }

}

/**
 * Message sent when LogService is unregistered.
 * Sent from the ActorSystemActivator to a logger (as DefaultOsgiLogger).
 */
case object UnregisteringLogService
