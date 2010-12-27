/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import java.util.concurrent._
import atomic. {AtomicInteger, AtomicBoolean, AtomicReference, AtomicLong}

import akka.util.{Switch, ReentrantGuard, Logging, HashCode, ReflectiveAccess}
import akka.actor._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class MessageInvocation(val receiver: ActorRef,
                              val message: Any,
                              val sender: Option[ActorRef],
                              val senderFuture: Option[CompletableFuture[Any]]) {
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
     "]"
  }
}

object MessageDispatcher {
  val UNSCHEDULED = 0
  val SCHEDULED = 1
  val RESCHEDULED = 2
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDispatcher extends MailboxFactory with Logging {
  import MessageDispatcher._

  protected val uuids = new ConcurrentSkipListSet[Uuid]
  protected val guard = new ReentrantGuard
  protected val active = new Switch(false)

  private var shutdownSchedule = UNSCHEDULED //This can be non-volatile since it is protected by guard withGuard

  /**
   *  Creates and returns a mailbox for the given actor.
   */
  def createMailbox(mailboxImplClassname: String, actorRef: ActorRef): MessageQueue = {
    ReflectiveAccess.createInstance(
      mailboxImplClassname,
      Array(classOf[ActorRef]),
      Array(actorRef).asInstanceOf[Array[AnyRef]],
      ReflectiveAccess.loader)
    .getOrElse(throw new IllegalActorStateException(
        "Could not create mailbox [" + mailboxImplClassname + "] for actor [" + actorRef + "]"))
    .asInstanceOf[MessageQueue]
  }

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

  private[akka] def register(actorRef: ActorRef) {
    if (actorRef.mailbox eq null) actorRef.mailbox = createMailbox(actorRef)
    uuids add actorRef.uuid
    if (active.isOff) {
      active.switchOn {
        start
      }
    }
  }

  private[akka] def unregister(actorRef: ActorRef) = {
    if (uuids remove actorRef.uuid) {
      actorRef.mailbox = null
      if (uuids.isEmpty){
        shutdownSchedule match {
          case UNSCHEDULED =>
            shutdownSchedule = SCHEDULED
            Scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
          case SCHEDULED =>
            shutdownSchedule = RESCHEDULED
          case RESCHEDULED => //Already marked for reschedule
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
          log.slf4j.error("stopAllLinkedActors couldn't find linked actor: " + uuid)
      }
    }
  }

  private val shutdownAction = new Runnable {
    def run = guard withGuard {
      shutdownSchedule match {
        case RESCHEDULED =>
          shutdownSchedule = SCHEDULED
          Scheduler.scheduleOnce(this, timeoutMs, TimeUnit.MILLISECONDS)
        case SCHEDULED =>
          if (uuids.isEmpty()) {
            active switchOff {
              shutdown // shut down in the dispatcher's references is zero
            }
          }
          shutdownSchedule = UNSCHEDULED
        case UNSCHEDULED => //Do nothing
      }
    }
  }

  /**
   * When the dispatcher no longer has any actors registered, how long will it wait until it shuts itself down, in Ms
   * defaulting to your akka configs "akka.actor.dispatcher-shutdown-timeout" or otherwise, 1 Second
   */
  private[akka] def timeoutMs: Long = Dispatchers.DEFAULT_SHUTDOWN_TIMEOUT.toMillis

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
  private[akka] def dispatch(invocation: MessageInvocation): Unit

  /**
   * Called one time every time an actor is attached to this dispatcher and this dispatcher was previously shutdown
   */
  private[akka] def start: Unit

  /**
   * Called one time every time an actor is detached from this dispatcher and this dispatcher has no actors left attached
   */
  private[akka] def shutdown: Unit

  /**
   * Returns the size of the mailbox for the specified actor
   */
  def mailboxSize(actorRef: ActorRef): Int
}
