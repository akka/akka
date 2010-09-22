/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.actor.{Actor, ActorRef, ActorInitializationException}

import org.multiverse.commitbarriers.CountDownCommitBarrier
import se.scalablesolutions.akka.AkkaException
import java.util.{Queue, List}
import java.util.concurrent._
import se.scalablesolutions.akka.actor.Uuid
import se.scalablesolutions.akka.util.{SimpleLock, Duration, HashCode, Logging}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class MessageInvocation(val receiver: ActorRef,
                              val message: Any,
                              val sender: Option[ActorRef],
                              val senderFuture: Option[CompletableFuture[Any]],
                              val transactionSet: Option[CountDownCommitBarrier]) {
  if (receiver eq null) throw new IllegalArgumentException("receiver is null")

  def invoke = try {
    receiver.invoke(this)
  } catch {
    case e: NullPointerException => throw new ActorInitializationException(
      "Don't call 'self ! message' in the Actor's constructor (e.g. body of the class).")
  }

  override def hashCode(): Int = synchronized {
    var result = HashCode.SEED
    result = HashCode.hash(result, receiver.actor)
    result = HashCode.hash(result, message.asInstanceOf[AnyRef])
    result
  }

  override def equals(that: Any): Boolean = synchronized {
    that != null &&
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].receiver.actor == receiver.actor &&
    that.asInstanceOf[MessageInvocation].message == message
  }

  override def toString = synchronized {
    "MessageInvocation[" +
     "\n\tmessage = " + message +
     "\n\treceiver = " + receiver +
     "\n\tsender = " + sender +
     "\n\tsenderFuture = " + senderFuture +
     "\n\ttransactionSet = " + transactionSet +
     "]"
  }
}

class MessageQueueAppendFailedException(message: String) extends AkkaException(message)

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageQueue {
  val dispatcherLock = new SimpleLock
  def enqueue(handle: MessageInvocation)
  def dequeue(): MessageInvocation
  def size: Int
  def isEmpty: Boolean
}

/* Tells the dispatcher that it should create a bounded mailbox with the specified push timeout
 * (If capacity > 0)
 */
case class MailboxConfig(capacity: Int, pushTimeOut: Option[Duration], blockingDequeue: Boolean) {

  /**
   * Creates a MessageQueue (Mailbox) with the specified properties
   * bounds = whether the mailbox should be bounded (< 0 means unbounded)
   * pushTime = only used if bounded, indicates if and how long an enqueue should block
   * blockDequeue = whether dequeues should block or not
   *
   * The bounds + pushTime generates a MessageQueueAppendFailedException if enqueue times out
   */
  def newMailbox(bounds: Int = capacity,
                 pushTime: Option[Duration] = pushTimeOut,
                 blockDequeue: Boolean = blockingDequeue) : MessageQueue =
    if (capacity > 0) new DefaultBoundedMessageQueue(bounds,pushTime,blockDequeue)
    else new DefaultUnboundedMessageQueue(blockDequeue)
}

class DefaultUnboundedMessageQueue(blockDequeue: Boolean) extends LinkedBlockingQueue[MessageInvocation] with MessageQueue {
  final def enqueue(handle: MessageInvocation) {
    this add handle
  }

  final def dequeue(): MessageInvocation =
    if (blockDequeue) this.take()
    else this.poll()
}

class DefaultBoundedMessageQueue(capacity: Int, pushTimeOut: Option[Duration], blockDequeue: Boolean) extends LinkedBlockingQueue[MessageInvocation](capacity) with MessageQueue {
  final def enqueue(handle: MessageInvocation) {
    if (pushTimeOut.isDefined) {
      if(!this.offer(handle,pushTimeOut.get.length,pushTimeOut.get.unit))
        throw new MessageQueueAppendFailedException("Couldn't enqueue message " + handle + " to " + toString)
    }
    else {
      this put handle
    }
  }

  final def dequeue(): MessageInvocation =
    if (blockDequeue) this.take()
    else this.poll()

}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDispatcher extends Logging {
  protected val uuids = new ConcurrentSkipListSet[Uuid]

  def dispatch(invocation: MessageInvocation)

  def start

  def shutdown

  def register(actorRef: ActorRef) {
    if(actorRef.mailbox eq null)
      actorRef.mailbox = createMailbox(actorRef)
    uuids add actorRef.uuid
  }
  def unregister(actorRef: ActorRef) = {
    uuids remove actorRef.uuid
    actorRef.mailbox = null
    if (canBeShutDown) shutdown // shut down in the dispatcher's references is zero
  }
  
  def canBeShutDown: Boolean = uuids.isEmpty

  def isShutdown: Boolean

  /**
   * Returns the size of the mailbox for the specified actor
   */
  def mailboxSize(actorRef: ActorRef):Int

  /**
   *  Creates and returns a mailbox for the given actor
   */
  protected def createMailbox(actorRef: ActorRef): AnyRef = null
}