/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.event

import akka.dispatch.MessageQueue
import akka.dispatch.MailboxType
import akka.dispatch.UnboundedMailbox
import com.typesafe.config.Config
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.dispatch.ProducesMessageQueue

trait LoggerMessageQueueSemantics

/**
 * INTERNAL API
 */
private[akka] class LoggerMailboxType(settings: ActorSystem.Settings, config: Config) extends MailboxType
  with ProducesMessageQueue[LoggerMailbox] {

  override def create(owner: Option[ActorRef], system: Option[ActorSystem]) = (owner, system) match {
    case (Some(o), Some(s)) ⇒ new LoggerMailbox(o, s)
    case _                  ⇒ throw new IllegalArgumentException("no mailbox owner or system given")
  }
}

/**
 * INTERNAL API
 */
private[akka] class LoggerMailbox(owner: ActorRef, system: ActorSystem)
  extends UnboundedMailbox.MessageQueue with LoggerMessageQueueSemantics {

  override def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    if (hasMessages) {
      var envelope = dequeue
      // Drain all remaining messages to the StandardOutLogger.
      // cleanUp is called after switching out the mailbox, which is why
      // this kind of look works without a limit.
      while (envelope ne null) {
        // Logging.StandardOutLogger is a MinimalActorRef, i.e. not a "real" actor
        Logging.StandardOutLogger.tell(envelope.message, envelope.sender)
        envelope = dequeue
      }
    }
    super.cleanUp(owner, deadLetters)
  }
}
