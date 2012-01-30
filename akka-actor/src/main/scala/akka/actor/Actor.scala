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
 */
trait AutoReceivedMessage extends Serializable

/**
 * Marker trait to indicate that a message might be potentially harmful,
 * this is used to block messages coming in over remoting.
 */
trait PossiblyHarmful

/**
 * Marker trait to signal that this class should not be verified for serializability.
 */
trait NoSerializationVerificationNeeded

case class Failed(cause: Throwable) extends AutoReceivedMessage with PossiblyHarmful

case object PoisonPill extends AutoReceivedMessage with PossiblyHarmful

case object Kill extends AutoReceivedMessage with PossiblyHarmful

case class Terminated(@BeanProperty actor: ActorRef) extends PossiblyHarmful

case object ReceiveTimeout extends PossiblyHarmful

/**
 * ActorRefFactory.actorSelection returns a special ref which sends these
 * nested path descriptions whenever using ! on them, the idea being that the
 * message is delivered by active routing of the various actors involved.
 */
sealed trait SelectionPath extends AutoReceivedMessage
case class SelectChildName(name: String, next: Any) extends SelectionPath
case class SelectChildPattern(pattern: Pattern, next: Any) extends SelectionPath
case class SelectParent(next: Any) extends SelectionPath

// Exceptions for Actors
class IllegalActorStateException private[akka] (message: String, cause: Throwable = null)
  extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

class ActorKilledException private[akka] (message: String, cause: Throwable)
  extends AkkaException(message, cause)
  with NoStackTrace {
  def this(msg: String) = this(msg, null);
}

case class InvalidActorNameException(message: String) extends AkkaException(message)

case class ActorInitializationException private[akka] (actor: ActorRef, message: String, cause: Throwable = null)
  extends AkkaException(message, cause)
  with NoStackTrace {
  def this(msg: String) = this(null, msg, null);
}

class ActorTimeoutException private[akka] (message: String, cause: Throwable = null)
  extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

class InvalidMessageException private[akka] (message: String, cause: Throwable = null)
  extends AkkaException(message, cause)
  with NoStackTrace {
  def this(msg: String) = this(msg, null);
}

case class DeathPactException private[akka] (dead: ActorRef)
  extends AkkaException("Monitored actor [" + dead + "] terminated")
  with NoStackTrace

// must not pass InterruptedException to other threads
case class ActorInterruptedException private[akka] (cause: Throwable)
  extends AkkaException(cause.getMessage, cause)
  with NoStackTrace

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
  case class Success(status: AnyRef) extends Status
  case class Failure(cause: Throwable) extends Status
}

trait ActorLogging { this: Actor ⇒
  val log = akka.event.Logging(context.system, context.self)
}

object Actor {

  type Receive = PartialFunction[Any, Unit]

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
          "\n\t\t'val actor = system.actorOf(Props(new MyActor(..)))' (to create a top level actor from the ActorSystem),        or" +
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
   */
  final def sender: ActorRef = context.sender

  /**
   * This defines the initial actor behavior, it must return a partial function
   * with the actor logic.
   */
  protected def receive: Receive

  /**
   * User overridable definition the strategy to use for supervising
   * child actors.
   */
  def supervisorStrategy(): SupervisorStrategy = SupervisorStrategy.defaultStrategy

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

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  /**
   * For Akka internal use only.
   */
  private[akka] final def apply(msg: Any) = {
    msg match {
      case msg if behaviorStack.head.isDefinedAt(msg) ⇒ behaviorStack.head.apply(msg)
      case unknown                                    ⇒ unhandled(unknown)
    }
  }

  /**
   * For Akka internal use only.
   */
  private[akka] def pushBehavior(behavior: Receive): Unit = {
    behaviorStack = behaviorStack.push(behavior)
  }

  /**
   * For Akka internal use only.
   */
  private[akka] def popBehavior(): Unit = {
    val original = behaviorStack
    val popped = original.pop
    behaviorStack = if (popped.isEmpty) original else popped
  }

  /**
   * For Akka internal use only.
   */
  private[akka] def clearBehaviorStack(): Unit = Stack.empty[Receive].push(behaviorStack.last)

  private var behaviorStack: Stack[Receive] = Stack.empty[Receive].push(receive)
}

