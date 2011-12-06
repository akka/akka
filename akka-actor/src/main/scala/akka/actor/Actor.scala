/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import DeploymentConfig._
import akka.dispatch._
import akka.routing._
import akka.util.Duration
import akka.remote.RemoteSupport
import akka.japi.{ Creator, Procedure }
import akka.serialization.{ Serializer, Serialization }
import akka.event.Logging.Debug
import akka.event.LogSource
import akka.experimental
import akka.AkkaException
import scala.reflect.BeanProperty
import scala.util.control.NoStackTrace
import com.eaio.uuid.UUID
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.{ Collection ⇒ JCollection }
import java.util.regex.Pattern

/**
 * Marker trait to show which Messages are automatically handled by Akka
 */
sealed trait AutoReceivedMessage extends Serializable

trait PossiblyHarmful

case class HotSwap(code: ActorContext ⇒ Actor.Receive, discardOld: Boolean = true) extends AutoReceivedMessage {

  /**
   * Java API
   */
  def this(code: akka.japi.Function[ActorContext, Procedure[Any]], discardOld: Boolean) = {
    this((context: ActorContext) ⇒ {
      val behavior = code(context)
      val result: Actor.Receive = { case msg ⇒ behavior(msg) }
      result
    }, discardOld)
  }

  /**
   *  Java API with default non-stacking behavior
   */
  def this(code: akka.japi.Function[ActorContext, Procedure[Any]]) = this(code, true)
}

case class Failed(cause: Throwable) extends AutoReceivedMessage with PossiblyHarmful

case object RevertHotSwap extends AutoReceivedMessage with PossiblyHarmful

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
 * This message is thrown by default when an Actors behavior doesn't match a message
 */
case class UnhandledMessageException(msg: Any, ref: ActorRef = null) extends Exception {

  def this(msg: String) = this(msg, null)

  // constructor with 'null' ActorRef needed to work with client instantiation of remote exception
  override def getMessage =
    if (ref ne null) "Actor [%s] does not handle [%s]".format(ref, msg)
    else "Actor does not handle [%s]".format(msg)

  override def fillInStackTrace() = this //Don't waste cycles generating stack trace
}

/**
 * Classes for passing status back to the sender.
 * Used for internal ACKing protocol. But exposed as utility class for user-specific ACKing protocols as well.
 */
object Status {
  sealed trait Status extends Serializable
  case class Success(status: AnyRef) extends Status
  case class Failure(cause: Throwable) extends Status
}

case class Timeout(duration: Duration) {
  def this(timeout: Long) = this(Duration(timeout, TimeUnit.MILLISECONDS))
  def this(length: Long, unit: TimeUnit) = this(Duration(length, unit))
}

object Timeout {
  /**
   * A timeout with zero duration, will cause most requests to always timeout.
   */
  val zero = new Timeout(Duration.Zero)

  /**
   * A Timeout with infinite duration. Will never timeout. Use extreme caution with this
   * as it may cause memory leaks, blocked threads, or may not even be supported by
   * the receiver, which would result in an exception.
   */
  val never = new Timeout(Duration.Inf)

  def apply(timeout: Long) = new Timeout(timeout)
  def apply(length: Long, unit: TimeUnit) = new Timeout(length, unit)

  implicit def durationToTimeout(duration: Duration) = new Timeout(duration)
  implicit def intToTimeout(timeout: Int) = new Timeout(timeout)
  implicit def longToTimeout(timeout: Long) = new Timeout(timeout)
}

trait ActorLogging { this: Actor ⇒
  val log = akka.event.Logging(context.system.eventStream, context.self)
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
 * <p/>
 * An actor has a well-defined (non-cyclic) life-cycle.
 * <pre>
 * => RUNNING (created and started actor) - can receive messages
 * => SHUTDOWN (when 'stop' or 'exit' is invoked) - can't do anything
 * </pre>
 *
 * <p/>
 * The Actor's own ActorRef is available in the 'self' member variable.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Actor {

  import Actor._

  // to make type Receive known in subclasses without import
  type Receive = Actor.Receive

  /**
   * Stores the context for this actor, including self, sender, and hotswap.
   * It is implicit to support operations such as `forward`.
   *
   * [[akka.actor.ActorContext]] is the Scala API. `getContext` returns a
   * [[akka.actor.UntypedActorContext]], which is the Java API of the actor
   * context.
   */
  @transient
  protected[akka] implicit val context: ActorContext = {
    val contextStack = ActorCell.contextStack.get

    def noContextError =
      throw new ActorInitializationException(
        "\n\tYou cannot create an instance of " + getClass.getName + " explicitly using the constructor (new)." +
          "\n\tYou have to use one of the factory methods to create a new actor. Either use:" +
          "\n\t\t'val actor = context.actorOf[MyActor]'        (to create a supervised child actor from within an actor), or" +
          "\n\t\t'val actor = system.actorOf(new MyActor(..))' (to create a top level actor from the ActorSystem),        or" +
          "\n\t\t'val actor = context.actorOf[MyActor]'        (to create a supervised child actor from within an actor), or" +
          "\n\t\t'val actor = system.actorOf(new MyActor(..))' (to create a top level actor from the ActorSystem)")

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
   * User overridable callback/setting.
   * <p/>
   * Partial function implementing the actor logic.
   * To be implemented by concrete actor class.
   * <p/>
   * Example code:
   * <pre>
   *   def receive = {
   *     case Ping =&gt;
   *       println("got a 'Ping' message")
   *       sender ! "pong"
   *
   *     case OneWay =&gt;
   *       println("got a 'OneWay' message")
   *
   *     case unknown =&gt;
   *       println("unknown message: " + unknown)
   * }
   * </pre>
   */
  protected def receive: Receive

  /**
   * User overridable callback.
   * <p/>
   * Is called when an Actor is started.
   * Empty default implementation.
   */
  def preStart() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop()' is invoked.
   * Empty default implementation.
   */
  def postStop() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called on a crashed Actor right BEFORE it is restarted to allow clean
   * up of resources before Actor is terminated.
   * By default it calls postStop()
   */
  def preRestart(reason: Throwable, message: Option[Any]) { postStop() }

  /**
   * User overridable callback.
   * <p/>
   * Is called right AFTER restart on the newly created Actor to allow reinitialization after an Actor crash.
   * By default it calls preStart()
   */
  def postRestart(reason: Throwable) { preStart() }

  /**
   * User overridable callback.
   * <p/>
   * Is called when a message isn't handled by the current behavior of the actor
   * by default it does: EventHandler.warning(self, message)
   */
  def unhandled(message: Any) {
    message match {
      case Terminated(dead) ⇒ throw new DeathPactException(dead)
      case _                ⇒ throw new UnhandledMessageException(message, self)
    }
  }

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] final def apply(msg: Any) = {
    val behaviorStack = context.hotswap
    msg match {
      case msg if behaviorStack.nonEmpty && behaviorStack.head.isDefinedAt(msg) ⇒ behaviorStack.head.apply(msg)
      case msg if behaviorStack.isEmpty && processingBehavior.isDefinedAt(msg) ⇒ processingBehavior.apply(msg)
      case unknown ⇒ unhandled(unknown)
    }
  }

  private[this] val processingBehavior = receive //ProcessingBehavior is the original behavior
}

