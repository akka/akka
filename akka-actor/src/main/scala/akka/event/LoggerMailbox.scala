/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event

import akka.dispatch.MessageQueue
import akka.dispatch.MailboxType
import akka.dispatch.UnboundedMailbox
import com.typesafe.config.Config
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.dispatch.ProducesMessageQueue
import akka.event.Logging.LogEvent
import akka.util.unused

trait LoggerMessageQueueSemantics

/**
 * INTERNAL API
 */
private[akka] class LoggerMailboxType(@unused settings: ActorSystem.Settings, @unused config: Config)
    extends MailboxType
    with ProducesMessageQueue[LoggerMailbox] {

  override def create(owner: Option[ActorRef], system: Option[ActorSystem]) = (owner, system) match {
    case (Some(o), Some(s)) => new LoggerMailbox(o, s)
    case _                  => throw new IllegalArgumentException("no mailbox owner or system given")
  }
}

/**
 * INTERNAL API
 */
private[akka] class LoggerMailbox(@unused owner: ActorRef, system: ActorSystem)
    extends UnboundedMailbox.MessageQueue
    with LoggerMessageQueueSemantics {

  override def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    if (hasMessages) {
      val logLevel = system.eventStream.logLevel
      var envelope = dequeue
      // Drain all remaining messages to the StandardOutLogger.
      // cleanUp is called after switching out the mailbox, which is why
      // this kind of look works without a limit.
      val loggingEnabled = Logging.AllLogLevels.contains(logLevel)
      while (envelope ne null) {
        // skip logging if level is OFF
        if (loggingEnabled)
          envelope.message match {
            case e: LogEvent if e.level <= logLevel =>
              // Logging.StandardOutLogger is a MinimalActorRef, i.e. not a "real" actor
              Logging.StandardOutLogger.tell(envelope.message, envelope.sender)
            case _ => // skip
          }

        envelope = dequeue
      }
    }
    super.cleanUp(owner, deadLetters)
  }
}
