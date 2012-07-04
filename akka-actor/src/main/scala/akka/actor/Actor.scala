/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.AkkaException
import scala.reflect.BeanProperty
import scala.util.control.NoStackTrace
import scala.collection.immutable.Stack
import java.util.regex.Pattern

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
private[akka] case class Failed(cause: Throwable) extends AutoReceivedMessage with PossiblyHarmful

abstract class PoisonPill extends AutoReceivedMessage with PossiblyHarmful

/**
 * A message all Actors will understand, that when processed will terminate the Actor permanently.
 */
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
case object Kill extends Kill {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

/**
 * When Death Watch is used, the watcher will receive a Terminated(watched) message when watched is terminated.
 */
case class Terminated(@BeanProperty actor: ActorRef)(@BeanProperty val existenceConfirmed: Boolean)

abstract class ReceiveTimeout extends PossiblyHarmful

/**
 * When using ActorContext.setReceiveTimeout, the singleton instance of ReceiveTimeout will be sent
 * to the Actor when there hasn't been any message for that long.
 */
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
sealed trait SelectionPath extends AutoReceivedMessage

/**
 * Internal use only
 */
private[akka] case class SelectChildName(name: String, next: Any) extends SelectionPath

/**
 * Internal use only
 */
private[akka] case class SelectChildPattern(pattern: Pattern, next: Any) extends SelectionPath

/**
 * Internal use only
 */
private[akka] case class SelectParent(next: Any) extends SelectionPath

/**
 * IllegalActorStateException is thrown when a core invariant in the Actor implementation has been violated.
 * For instance, if you try to create an Actor that doesn't extend Actor.
 */
class IllegalActorStateException private[akka] (message: String, cause: Throwable = null)
  extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null)
}

/**
 * ActorKilledException is thrown when an Actor receives the akka.actor.Kill message
 */
class ActorKilledException private[akka] (message: String, cause: Throwable)
  extends AkkaException(message, cause)
  with NoStackTrace {
  def this(msg: String) = this(msg, null)
}

/**
 * An InvalidActorNameException is thrown when you try to convert something, usually a String, to an Actor name
 * which doesn't validate.
 */
class InvalidActorNameException(message: String) extends AkkaException(message)

/**
 * An ActorInitializationException is thrown when the the initialization logic for an Actor fails.
 */
class ActorInitializationException private[akka] (val actor: ActorRef, message: String, cause: Throwable)
  extends AkkaException(message, cause) {
  def this(msg: String) = this(null, msg, null)
  def this(actor: ActorRef, msg: String) = this(actor, msg, null)
}

/**
 * A PreRestartException is thrown when the preRestart() method failed.
 *
 * @param actor is the actor whose preRestart() hook failed
 * @param cause is the exception thrown by that actor within preRestart()
 * @param origCause is the exception which caused the restart in the first place
 * @param msg is the message which was optionally passed into preRestart()
 */
class PreRestartException private[akka] (actor: ActorRef, cause: Throwable, val origCause: Throwable, val msg: Option[Any])
  extends ActorInitializationException(actor, "exception in preRestart(" + origCause + ", " + msg + ")", cause) {
}

/**
 * A PostRestartException is thrown when constructor or postRestart() method 
 * fails during a restart attempt.
 *
 * @param actor is the actor whose constructor or postRestart() hook failed
 * @param cause is the exception thrown by that actor within preRestart()
 * @param origCause is the exception which caused the restart in the first place
 */
class PostRestartException private[akka] (actor: ActorRef, cause: Throwable, val origCause: Throwable)
  extends ActorInitializationException(actor, "exception post restart (" + origCause + ")", cause) {
}

/**
 * InvalidMessageException is thrown when an invalid message is sent to an Actor.
 * Technically it's only "null" which is an InvalidMessageException but who knows,
 * there might be more of them in the future, or not.
 */
class InvalidMessageException private[akka] (message: String, cause: Throwable = null)
  extends AkkaException(message, cause)
  with NoStackTrace {
  def this(msg: String) = this(msg, null)
}

/**
 * A DeathPactException is thrown by an Actor that receives a Terminated(someActor) message
 * that it doesn't handle itself, effectively crashing the Actor and escalating to the supervisor.
 */
case class DeathPactException private[akka] (dead: ActorRef)
  extends AkkaException("Monitored actor [" + dead + "] terminated")
  with NoStackTrace

/**
 * When an InterruptedException is thrown inside an Actor, it is wrapped as an ActorInterruptedException as to
 * avoid cascading interrupts to other threads than the originally interrupted one.
 */
class ActorInterruptedException private[akka] (cause: Throwable) extends AkkaException(cause.getMessage, cause) with NoStackTrace

/**
 * This message is published to the EventStream whenever an Actor receives a message it doesn't understand
 */
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
  case class Success(status: AnyRef) extends Status

  /**
   * This class/message type is preferably used to indicate failure of some operation performed.
   * As an example, it is used to signal failure with AskSupport is used (ask/?).
   */
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
  object emptyBehavior extends Receive {
    def isDefinedAt(x: Any) = false
    def apply(x: Any) = throw new UnsupportedOperationException("Empty behavior apply()")
  }

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

    def noContextError =
      throw new ActorInitializationException(
        "\n\tYou cannot create an instance of [" + getClass.getName + "] explicitly using the constructor (new)." +
          "\n\tYou have to use one of the factory methods to create a new actor. Either use:" +
          "\n\t\t'val actor = context.actorOf(Props[MyActor])'        (to create a supervised child actor from within an actor), or" +
          "\n\t\t'val actor = system.actorOf(Props(new MyActor(..)))' (to create a top level actor from the ActorSystem)")

    if (contextStack.isEmpty) noContextError
    val c = contextStack.head
    if (c eq null) noContextError
    ActorCell.contextStack.set(contextStack.push(null))
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

