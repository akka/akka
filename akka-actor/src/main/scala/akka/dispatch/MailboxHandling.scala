/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.actor.{Actor, ActorType, ActorRef, ActorInitializationException}
import akka.util.ReflectiveAccess.AkkaCloudModule
import akka.AkkaException

import java.util.{Queue, List}
import java.util.concurrent._
import concurrent.forkjoin.LinkedTransferQueue
import akka.util._

class MessageQueueAppendFailedException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageQueue {
  val dispatcherLock = new SimpleLock
  val suspended = new Switch(false)
  def enqueue(handle: MessageInvocation)
  def dequeue(): MessageInvocation
  def size: Int
  def isEmpty: Boolean
}

/**
 * Mailbox configuration.
 */
sealed trait MailboxType

abstract class TransientMailboxType(val blocking: Boolean = false) extends MailboxType
case class UnboundedMailbox(block: Boolean = false) extends TransientMailboxType(block)
case class BoundedMailbox(
  block: Boolean            = false,
  val capacity: Int         = { if (Dispatchers.MAILBOX_CAPACITY < 0) Int.MaxValue else Dispatchers.MAILBOX_CAPACITY },
  val pushTimeOut: Duration = Dispatchers.MAILBOX_PUSH_TIME_OUT) extends TransientMailboxType(block) {
  if (capacity < 0)        throw new IllegalArgumentException("The capacity for BoundedMailbox can not be negative")
  if (pushTimeOut eq null) throw new IllegalArgumentException("The push time-out for BoundedMailbox can not be null")
}

abstract class DurableMailboxType(val serializer: AkkaCloudModule.Serializer) extends MailboxType {
  if (serializer eq null) throw new IllegalArgumentException("The serializer for DurableMailboxType can not be null")
}
case class FileBasedDurableMailbox(ser: AkkaCloudModule.Serializer) extends DurableMailboxType(ser)
case class RedisBasedDurableMailbox(ser: AkkaCloudModule.Serializer) extends DurableMailboxType(ser)
case class BeanstalkBasedDurableMailbox(ser: AkkaCloudModule.Serializer) extends DurableMailboxType(ser)
case class ZooKeeperBasedDurableMailbox(ser: AkkaCloudModule.Serializer) extends DurableMailboxType(ser)
case class AMQPBasedDurableMailbox(ser: AkkaCloudModule.Serializer) extends DurableMailboxType(ser)
case class JMSBasedDurableMailbox(ser: AkkaCloudModule.Serializer) extends DurableMailboxType(ser)

class DefaultUnboundedMessageQueue(blockDequeue: Boolean)
  extends LinkedBlockingQueue[MessageInvocation] with MessageQueue {

  final def enqueue(handle: MessageInvocation) {
    this add handle
  }

  final def dequeue(): MessageInvocation = {
    if (blockDequeue) this.take()
    else this.poll()
  }
}

class DefaultBoundedMessageQueue(capacity: Int, pushTimeOut: Duration, blockDequeue: Boolean)
  extends LinkedBlockingQueue[MessageInvocation](capacity) with MessageQueue {

  final def enqueue(handle: MessageInvocation) {
    if (pushTimeOut.toMillis > 0) {
      if (!this.offer(handle, pushTimeOut.length, pushTimeOut.unit))
        throw new MessageQueueAppendFailedException("Couldn't enqueue message " + handle + " to " + toString)
    } else this put handle
  }

  final def dequeue(): MessageInvocation =
    if (blockDequeue) this.take()
    else this.poll()
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MailboxFactory {

  val mailboxType: Option[MailboxType]

  /**
   * Creates a MessageQueue (Mailbox) with the specified properties.
   */
  private[akka] def createMailbox(actorRef: ActorRef): AnyRef =
    mailboxType.getOrElse(throw new IllegalStateException("No mailbox type defined")) match {
      case mb: TransientMailboxType => createTransientMailbox(actorRef, mb)
      case mb: DurableMailboxType   => createDurableMailbox(actorRef, mb)
    }

  /**
   *  Creates and returns a transient mailbox for the given actor.
   */
  private[akka] def createTransientMailbox(actorRef: ActorRef, mailboxType: TransientMailboxType): AnyRef

  /**
   *  Creates and returns a durable mailbox for the given actor.
   */
  private[akka] def createDurableMailbox(actorRef: ActorRef, mailboxType: DurableMailboxType): AnyRef
}
