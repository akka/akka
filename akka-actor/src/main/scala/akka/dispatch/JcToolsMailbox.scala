/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import akka.actor.DeadLetter
import akka.actor.InternalActorRef
import akka.actor.{ ActorRef, ActorSystem }
import com.typesafe.config.Config

import scala.concurrent.duration.Duration
import akka.jctools.queues.MpscGrowableArrayQueue

import scala.annotation.tailrec

case class VirtuallyUnboundedJCToolsMailbox(initialCapacity: Int, capacity: Int) extends MailboxType with ProducesMessageQueue[JCToolsMessageQueue] {
  if (capacity < 0) throw new IllegalArgumentException("The capacity for JCToolsMailbox can not be negative")

  def this(settings: ActorSystem.Settings, config: Config) =
    this(config.getInt("mailbox-initial-capacity"), config.getInt("mailbox-capacity"))

  final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
    new JCToolsMessageQueue(initialCapacity, capacity) with UnboundedMessageQueueSemantics
}

private[dispatch] abstract class JCToolsMessageQueue(initialCapacity: Int, capacity: Int) extends MpscGrowableArrayQueue[Envelope](initialCapacity, capacity) with MessageQueue {
  final def pushTimeOut: Duration = Duration.Undefined

  final def enqueue(receiver: ActorRef, handle: Envelope): Unit =
    if (!offer(handle))
      receiver.asInstanceOf[InternalActorRef].provider.deadLetters.tell(
        DeadLetter(handle.message, handle.sender, receiver), handle.sender
      )

  final def dequeue(): Envelope = poll()

  final def numberOfMessages: Int = size()

  final def hasMessages: Boolean = !isEmpty()

  @tailrec final def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    val envelope = dequeue()
    if (envelope ne null) {
      deadLetters.enqueue(owner, envelope)
      cleanUp(owner, deadLetters)
    }
  }
}
