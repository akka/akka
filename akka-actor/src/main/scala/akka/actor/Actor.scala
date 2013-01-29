/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.AkkaException
import scala.reflect.BeanProperty
import scala.util.control.NoStackTrace
import java.util.regex.Pattern
import scala.annotation.tailrec

/**
 * Marker trait to show which Messages are automatically handled by Akka
 * Internal use only
 */
private[akka] trait AutoReceivedMessage extends Serializable

/**
 * Marker trait to indicate that a message might be potentially harmful,
 * this is used to block messages coming in over remoting.
 */
trait PossiblyHarmful

/**
 * Marker trait to signal that this class should not be verified for serializability.
 */
trait NoSerializationVerificationNeeded

/**
 * Internal use only
 */
@SerialVersionUID(2L)
private[akka] case class Failed(cause: Throwable, uid: Int) extends AutoReceivedMessage with PossiblyHarmful

abstract class PoisonPill extends AutoReceivedMessage with PossiblyHarmful

/**
 * A message all Actors will understand, that when processed will terminate the Actor permanently.
 */
@SerialVersionUID(1L)
case object PoisonPill extends PoisonPill {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

abstract class Kill extends AutoReceivedMessage with PossiblyHarmful
/**
 * A message all Actors will understand, that when processed will make the Actor throw an ActorKilledException,
 * which will trigger supervision.
 */
@SerialVersionUID(1L)
case object Kill extends Kill {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * When Death Watch is used, the watcher will receive a Terminated(watched)
 * message when watched is terminated.
 * Terminated message can't be forwarded to another actor, since that actor
 * might not be watching the subject. Instead, if you need to forward Terminated
 * to another actor you should send the information in your own message.
 *
 * @param actor the watched actor that terminated
 * @param existenceConfirmed is false when the Terminated message was not sent
 *   directly from the watched actor, but derived from another source, such as
 *   when watching a non-local ActorRef, which might not have been resolved
 * @param addressTerminated the Terminated message was derived from
 *   that the remote node hosting the watched actor was detected as unreachable
 */
@SerialVersionUID(1L)
case class Terminated private[akka] (@BeanProperty actor: ActorRef)(
  @BeanProperty val existenceConfirmed: Boolean,
  @BeanProperty val addressTerminated: Boolean) extends AutoReceivedMessage with PossiblyHarmful

/**
 * INTERNAL API
 *
 * Used for remote death watch. Failure detector publish this to the
 * `eventStream` when a remote node is detected to be unreachable and/or decided to
 * be removed.
 * The watcher ([[akka.actor.DeathWatch]]) subscribes to the `eventStream`
 * and translates this event to [[akka.actor.Terminated]], which is sent itself.
 */
@SerialVersionUID(1L)
private[akka] case class AddressTerminated(address: Address) extends AutoReceivedMessage with PossiblyHarmful

abstract class ReceiveTimeout extends PossiblyHarmful

/**
 * When using ActorContext.setReceiveTimeout, the singleton instance of ReceiveTimeout will be sent
 * to the Actor when there hasn't been any message for that long.
 */
@SerialVersionUID(1L)
case object ReceiveTimeout extends ReceiveTimeout {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * ActorRefFactory.actorSelection returns a special ref which sends these
 * nested path descriptions whenever using ! on them, the idea being that the
 * message is delivered by active routing of the various actors involved.
 */
sealed trait SelectionPath extends AutoReceivedMessage with PossiblyHarmful

/**
 * Internal use only
 */
@SerialVersionUID(1L)
private[akka] case class SelectChildName(name: String, next: Any) extends SelectionPath

/**
 * Internal use only
 */
@SerialVersionUID(1L)
private[akka] case class SelectChildPattern(pattern: Pattern, next: Any) extends SelectionPath

/**
 * Internal use only
 */
@SerialVersionUID(1L)
private[akka] case class SelectParent(next: Any) extends SelectionPath

/**
 * IllegalActorStateException is thrown when a core invariant in the Actor implementation has been violated.
 * For instance, if you try to create an Actor that doesn't extend Actor.
 */
@SerialVersionUID(1L)
case class IllegalActorStateException private[akka] (message: String) extends AkkaException(message)

/**
 * ActorKilledException is thrown when an Actor receives the akka.actor.Kill message
 */
@SerialVersionUID(1L)
case class ActorKilledException private[akka] (message: String) extends AkkaException(message) with NoStackTrace

/**
 * An InvalidActorNameException is thrown when you try to convert something, usually a String, to an Actor name
 * which doesn't validate.
 */
@SerialVersionUID(1L)
case class InvalidActorNameException(message: String) extends AkkaException(message)

/**
 * An ActorInitializationException is thrown when the the initialization logic for an Actor fails.
 *
 * There is an extractor which works for ActorInitializationException and its subtypes:
 *
 * {{{
 * ex match {
 *   case ActorInitializationException(actor, message, cause) => ...
 * }
 * }}}
 */
@SerialVersionUID(1L)
class ActorInitializationException protected (actor: ActorRef, message: String, cause: Throwable)
  extends AkkaException(message, cause) {
  def getActor: ActorRef = actor
}
object ActorInitializationException {
  private[akka] def apply(actor: ActorRef, message: String, cause: Throwable = null): ActorInitializationException =
    new ActorInitializationException(actor, message, cause)
  private[akka] def apply(message: String): ActorInitializationException = new ActorInitializationException(null, message, null)
  def unapply(ex: ActorInitializationException): Option[(ActorRef, String, Throwable)] = Some((ex.getActor, ex.getMessage, ex.getCause))
}

/**
 * A PreRestartException is thrown when the preRestart() method failed; this
 * exception is not propagated to the supervisor, as it originates from the
 * already failed instance, hence it is only visible as log entry on the event
 * stream.
 *
 * @param actor is the actor whose preRestart() hook failed
 * @param cause is the exception thrown by that actor within preRestart()
 * @param origCause is the exception which caused the restart in the first place
 * @param msg is the message which was optionally passed into preRestart()
 */
@SerialVersionUID(1L)
case class PreRestartException private[akka] (actor: ActorRef, cause: Throwable, originalCause: Throwable, messageOption: Option[Any])
  extends ActorInitializationException(actor,
    "exception in preRestart(" +
      (if (originalCause == null) "null" else originalCause.getClass) + ", " +
      (messageOption match { case Some(m: AnyRef) ⇒ m.getClass; case _ ⇒ "None" }) +
      ")", cause)

/**
 * A PostRestartException is thrown when constructor or postRestart() method
 * fails during a restart attempt.
 *
 * @param actor is the actor whose constructor or postRestart() hook failed
 * @param cause is the exception thrown by that actor within preRestart()
 * @param origCause is the exception which caused the restart in the first place
 */
@SerialVersionUID(1L)
case class PostRestartException private[akka] (actor: ActorRef, cause: Throwable, originalCause: Throwable)
  extends ActorInitializationException(actor,
    "exception post restart (" + (if (originalCause == null) "null" else originalCause.getClass) + ")", cause)

/**
 * This is an extractor for retrieving the original cause (i.e. the first
 * failure) from a [[akka.actor.PostRestartException]]. In the face of multiple
 * “nested” restarts it will walk the origCause-links until it arrives at a
 * non-PostRestartException type.
 */
@SerialVersionUID(1L)
object OriginalRestartException {
  def unapply(ex: PostRestartException): Option[Throwable] = {
    @tailrec def rec(ex: PostRestartException): Option[Throwable] = ex match {
      case PostRestartException(_, _, e: PostRestartException) ⇒ rec(e)
      case PostRestartException(_, _, e)                       ⇒ Some(e)
    }
    rec(ex)
  }
}

/**
 * InvalidMessageException is thrown when an invalid message is sent to an Actor;
 * Currently only `null` is an invalid message.
 */
@SerialVersionUID(1L)
case class InvalidMessageException private[akka] (message: String) extends AkkaException(message)

/**
 * A DeathPactException is thrown by an Actor that receives a Terminated(someActor) message
 * that it doesn't handle itself, effectively crashing the Actor and escalating to the supervisor.
 */
@SerialVersionUID(1L)
case class DeathPactException private[akka] (dead: ActorRef)
  extends AkkaException("Monitored actor [" + dead + "] terminated")
  with NoStackTrace

/**
 * When an InterruptedException is thrown inside an Actor, it is wrapped as an ActorInterruptedException as to
 * avoid cascading interrupts to other threads than the originally interrupted one.
 */
@SerialVersionUID(1L)
class ActorInterruptedException private[akka] (cause: Throwable) extends AkkaException(cause.getMessage, cause)

/**
 * This message is published to the EventStream whenever an Actor receives a message it doesn't understand
 */
@SerialVersionUID(1L)
case class UnhandledMessage(@BeanProperty message: Any, @BeanProperty sender: ActorRef, @BeanProperty recipient: ActorRef)

/**
 * Classes for passing status back to the sender.
 * Used for internal ACKing protocol. But exposed as utility class for user-specific ACKing protocols as well.
 */
object Status {
  sealed trait Status extends Serializable

  /**
   * This class/message type is preferably used to indicate success of some operation performed.
   */
  @SerialVersionUID(1L)
  case class Success(status: AnyRef) extends Status

  /**
   * This class/message type is preferably used to indicate failure of some operation performed.
   * As an example, it is used to signal failure with AskSupport is used (ask/?).
   */
  @SerialVersionUID(1L)
  case class Failure(cause: Throwable) extends Status
}

/**
 * Mix in ActorLogging into your Actor to easily obtain a reference to a logger, which is available under the name "log".
 *
 * {{{
 * class MyActor extends Actor with ActorLogging {
 *   def receive = {
 *     case "pigdog" => log.info("We've got yet another pigdog on our hands")
 *   }
 * }
 * }}}
 */
trait ActorLogging { this: Actor ⇒
  val log = akka.event.Logging(context.system, this)
}

object Actor {
  /**
   * Type alias representing a Receive-expression for Akka Actors.
   */
  type Receive = PartialFunction[Any, Unit]

  /**
   * emptyBehavior is a Receive-expression that matches no messages at all, ever.
   */
  @SerialVersionUID(1L)
  object emptyBehavior extends Receive {
    def isDefinedAt(x: Any) = false
    def apply(x: Any) = throw new UnsupportedOperationException("Empty behavior apply()")
  }

  /**
   * Default placeholder (null) used for "!" to indicate that there is no sender of the message,
   * that will be translated to the receiving system's deadLetters.
   */
  final val noSender: ActorRef = null
}

/**
 * Actor base trait that should be extended by or mixed to create an Actor with the semantics of the 'Actor Model':
 * <a href="http://en.wikipedia.org/wiki/Actor_model">http://en.wikipedia.org/wiki/Actor_model</a>
 *
 * An actor has a well-defined (non-cyclic) life-cycle.
 *  - ''RUNNING'' (created and started actor) - can receive messages
 *  - ''SHUTDOWN'' (when 'stop' or 'exit' is invoked) - can't do anything
 *
 * The Actor's own [[akka.actor.ActorRef]] is available as `self`, the current
 * message’s sender as `sender` and the [[akka.actor.ActorContext]] as
 * `context`. The only abstract method is `receive` which shall return the
 * initial behavior of the actor as a partial function (behavior can be changed
 * using `context.become` and `context.unbecome`).
 *
 * {{{
 * class ExampleActor extends Actor {
 *
 *   override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
 *     case _: ArithmeticException      ⇒ Resume
 *     case _: NullPointerException     ⇒ Restart
 *     case _: IllegalArgumentException ⇒ Stop
 *     case _: Exception                ⇒ Escalate
 *   }
 *
 *   def receive = {
 *                                      // directly calculated reply
 *     case Request(r)               => sender ! calculate(r)
 *
 *                                      // just to demonstrate how to stop yourself
 *     case Shutdown                 => context.stop(self)
 *
 *                                      // error kernel with child replying directly to “customer”
 *     case Dangerous(r)             => context.actorOf(Props[ReplyToOriginWorker]).tell(PerformWork(r), sender)
 *
 *                                      // error kernel with reply going through us
 *     case OtherJob(r)              => context.actorOf(Props[ReplyToMeWorker]) ! JobRequest(r, sender)
 *     case JobReply(result, orig_s) => orig_s ! result
 *   }
 * }
 * }}}
 *
 * The last line demonstrates the essence of the error kernel design: spawn
 * one-off actors which terminate after doing their job, pass on `sender` to
 * allow direct reply if that is what makes sense, or round-trip the sender
 * as shown with the fictitious JobRequest/JobReply message pair.
 *
 * If you don’t like writing `context` you can always `import context._` to get
 * direct access to `actorOf`, `stop` etc. This is not default in order to keep
 * the name-space clean.
 */
trait Actor {

  import Actor._

  // to make type Receive known in subclasses without import
  type Receive = Actor.Receive

  /**
   * Stores the context for this actor, including self, and sender.
   * It is implicit to support operations such as `forward`.
   *
   * WARNING: Only valid within the Actor itself, so do not close over it and
   * publish it to other threads!
   *
   * [[akka.actor.ActorContext]] is the Scala API. `getContext` returns a
   * [[akka.actor.UntypedActorContext]], which is the Java API of the actor
   * context.
   */
  protected[akka] implicit val context: ActorContext = {
    val contextStack = ActorCell.contextStack.get
    if ((contextStack.isEmpty) || (contextStack.head eq null))
      throw ActorInitializationException(
        "\n\tYou cannot create an instance of [" + getClass.getName + "] explicitly using the constructor (new)." +
          "\n\tYou have to use one of the factory methods to create a new actor. Either use:" +
          "\n\t\t'val actor = context.actorOf(Props[MyActor])'        (to create a supervised child actor from within an actor), or" +
          "\n\t\t'val actor = system.actorOf(Props(new MyActor(..)))' (to create a top level actor from the ActorSystem)")
    val c = contextStack.head
    ActorCell.contextStack.set(null :: contextStack)
    c
  }

  /**
   * The 'self' field holds the ActorRef for this actor.
   * <p/>
   * Can be used to send messages to itself:
   * <pre>
   * self ! message
   * </pre>
   */
  implicit final val self = context.self //MUST BE A VAL, TRUST ME

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor,
   * else `deadLetters` in [[akka.actor.ActorSystem]].
   *
   * WARNING: Only valid within the Actor itself, so do not close over it and
   * publish it to other threads!
   */
  final def sender: ActorRef = context.sender

  /**
   * This defines the initial actor behavior, it must return a partial function
   * with the actor logic.
   */
  def receive: Receive

  /**
   * User overridable definition the strategy to use for supervising
   * child actors.
   */
  def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started.
   * Actors are automatically started asynchronously when created.
   * Empty default implementation.
   */
  def preStart() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called asynchronously after 'actor.stop()' is invoked.
   * Empty default implementation.
   */
  def postStop() {}

  /**
   * User overridable callback: '''By default it disposes of all children and then calls `postStop()`.'''
   * @param reason the Throwable that caused the restart to happen
   * @param message optionally the current message the actor processed when failing, if applicable
   * <p/>
   * Is called on a crashed Actor right BEFORE it is restarted to allow clean
   * up of resources before Actor is terminated.
   */
  def preRestart(reason: Throwable, message: Option[Any]) {
    context.children foreach context.stop
    postStop()
  }

  /**
   * User overridable callback: By default it calls `preStart()`.
   * @param reason the Throwable that caused the restart to happen
   * <p/>
   * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
   */
  def postRestart(reason: Throwable) { preStart() }

  /**
   * User overridable callback.
   * <p/>
   * Is called when a message isn't handled by the current behavior of the actor
   * by default it fails with either a [[akka.actor.DeathPactException]] (in
   * case of an unhandled [[akka.actor.Terminated]] message) or publishes an [[akka.actor.UnhandledMessage]]
   * to the actor's system's [[akka.event.EventStream]]
   */
  def unhandled(message: Any) {
    message match {
      case Terminated(dead) ⇒ throw new DeathPactException(dead)
      case _                ⇒ context.system.eventStream.publish(UnhandledMessage(message, sender, self))
    }
  }
}

