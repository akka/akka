/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import org.multiverse.commitbarriers.CountDownCommitBarrier

import java.util.concurrent._
import se.scalablesolutions.akka.util. {Switch, ReentrantGuard, Logging, HashCode}
import se.scalablesolutions.akka.actor._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class MessageInvocation(val receiver: ActorRef,
                              val message: Any,
                              val sender: Option[ActorRef],
                              val senderFuture: Option[CompletableFuture[Any]],
                              val transactionSet: Option[CountDownCommitBarrier]) {
  if (receiver eq null) throw new IllegalArgumentException("Receiver can't be null")

  def invoke = try {
    receiver.invoke(this)
  } catch {
    case e: NullPointerException => throw new ActorInitializationException(
      "Don't call 'self ! message' in the Actor's constructor (in Scala this means in the body of the class).")
  }

  override def hashCode(): Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, receiver.actor)
    result = HashCode.hash(result, message.asInstanceOf[AnyRef])
    result
  }

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[MessageInvocation] &&
    that.asInstanceOf[MessageInvocation].receiver.actor == receiver.actor &&
    that.asInstanceOf[MessageInvocation].message == message
  }

  override def toString = {
    "MessageInvocation[" +
     "\n\tmessage = " + message +
     "\n\treceiver = " + receiver +
     "\n\tsender = " + sender +
     "\n\tsenderFuture = " + senderFuture +
     "\n\ttransactionSet = " + transactionSet +
     "]"
  }
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDispatcher extends MailboxFactory with Logging {
  protected val uuids = new ConcurrentSkipListSet[Uuid]
  protected val guard = new ReentrantGuard
  protected val active = new Switch(false)

  /**
   * Attaches the specified actorRef to this dispatcher
   */
  final def attach(actorRef: ActorRef): Unit = guard withGuard {
    register(actorRef)
  }

  /**
   * Detaches the specified actorRef from this dispatcher
   */
  final def detach(actorRef: ActorRef): Unit = guard withGuard {
    unregister(actorRef)
  }

  private[akka] final def dispatchMessage(invocation: MessageInvocation): Unit = if (active.isOn) {
    dispatch(invocation)
  } else throw new IllegalActorStateException("Can't submit invocations to dispatcher since it's not started")

  protected def register(actorRef: ActorRef) {
    if (uuids.isEmpty()) {
      active.switchOn {
        start
      }
    }
    if (actorRef.mailbox eq null) actorRef.mailbox = createMailbox(actorRef)
    uuids add actorRef.uuid
  }
  
  protected def unregister(actorRef: ActorRef) = {
    if (uuids remove actorRef.uuid) {
      actorRef.mailbox = null
      if (uuids.isEmpty){
        active switchOff {
          shutdown // shut down in the dispatcher's references is zero
        }
      }
    }
  }

  /**
   * Traverses the list of actors (uuids) currently being attached to this dispatcher and stops those actors
   */
  def stopAllAttachedActors {
    val i = uuids.iterator
    while(i.hasNext()) {
      val uuid = i.next()
      ActorRegistry.actorFor(uuid) match {
        case Some(actor) => actor.stop
        case None =>
          log.error("stopAllLinkedActors couldn't find linked actor: " + uuid)
      }
    }
  }

  /**
   * After the call to this method, the dispatcher mustn't begin any new message processing for the specified reference
   */
  def suspend(actorRef: ActorRef): Unit

  /*
   * After the call to this method, the dispatcher must begin any new message processing for the specified reference
   */
  def resume(actorRef: ActorRef): Unit

  /**
   *   Will be called when the dispatcher is to queue an invocation for execution
   */
  protected def dispatch(invocation: MessageInvocation): Unit

  /**
   * Called one time every time an actor is attached to this dispatcher and this dispatcher was previously shutdown
   */
  protected def start: Unit

  /**
   * Called one time every time an actor is detached from this dispatcher and this dispatcher has no actors left attached
   */
  protected def shutdown: Unit

  /**
   * Returns the size of the mailbox for the specified actor
   */
  def mailboxSize(actorRef: ActorRef): Int
}