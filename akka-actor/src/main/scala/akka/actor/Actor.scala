/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import DeploymentConfig._
import akka.dispatch._
import akka.config._
import akka.routing._
import akka.util.Duration
import akka.remote.RemoteSupport
import akka.cluster.ClusterNode
import akka.japi.{ Creator, Procedure }
import akka.serialization.{ Serializer, Serialization }
import akka.event.Logging.Debug
import akka.experimental
import akka.AkkaException

import scala.reflect.BeanProperty
import scala.util.control.NoStackTrace

import com.eaio.uuid.UUID

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.{ Collection ⇒ JCollection }

/**
 * Marker trait to show which Messages are automatically handled by Akka
 */
sealed trait AutoReceivedMessage extends Serializable

trait PossiblyHarmful

case class HotSwap(code: ActorRef ⇒ Actor.Receive, discardOld: Boolean = true) extends AutoReceivedMessage {

  /**
   * Java API
   */
  def this(code: akka.japi.Function[ActorRef, Procedure[Any]], discardOld: Boolean) = {
    this((self: ActorRef) ⇒ {
      val behavior = code(self)
      val result: Actor.Receive = { case msg ⇒ behavior(msg) }
      result
    }, discardOld)
  }

  /**
   *  Java API with default non-stacking behavior
   */
  def this(code: akka.japi.Function[ActorRef, Procedure[Any]]) = this(code, true)
}

case class Failed(cause: Throwable) extends AutoReceivedMessage with PossiblyHarmful

case object ChildTerminated extends AutoReceivedMessage with PossiblyHarmful

case object RevertHotSwap extends AutoReceivedMessage with PossiblyHarmful

case object PoisonPill extends AutoReceivedMessage with PossiblyHarmful

case object Kill extends AutoReceivedMessage with PossiblyHarmful

case class Terminated(@BeanProperty actor: ActorRef) extends PossiblyHarmful

case object ReceiveTimeout extends PossiblyHarmful

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

case class ActorInitializationException private[akka] (actor: ActorRef, message: String, cause: Throwable = null)
  extends AkkaException(message, cause) with NoStackTrace {
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
  extends AkkaException("monitored actor " + dead + " terminated")
  with NoStackTrace

// must not pass InterruptedException to other threads
case class ActorInterruptedException private[akka] (cause: Throwable) extends AkkaException(cause.getMessage, cause) with NoStackTrace

/**
 * This message is thrown by default when an Actors behavior doesn't match a message
 */
case class UnhandledMessageException(msg: Any, ref: ActorRef = null) extends Exception {

  def this(msg: String) = this(msg, null)

  // constructor with 'null' ActorRef needed to work with client instantiation of remote exception
  override def getMessage =
    if (ref ne null) "Actor %s does not handle [%s]".format(ref, msg)
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
  implicit def defaultTimeout(implicit app: ActorSystem) = app.AkkaConfig.ActorTimeout
}

trait ActorLogging { this: Actor ⇒
  val log = akka.event.Logging(app.eventStream, context.self)
}

object Actor {

  type Receive = PartialFunction[Any, Unit]

  /**
   * This decorator adds invocation logging to a Receive function.
   */
  class LoggingReceive(source: AnyRef, r: Receive)(implicit app: ActorSystem) extends Receive {
    def isDefinedAt(o: Any) = {
      val handled = r.isDefinedAt(o)
      app.eventStream.publish(Debug(source, "received " + (if (handled) "handled" else "unhandled") + " message " + o))
      handled
    }
    def apply(o: Any): Unit = r(o)
  }

  object LoggingReceive {
    def apply(source: AnyRef, r: Receive)(implicit app: ActorSystem): Receive = r match {
      case _: LoggingReceive ⇒ r
      case _                 ⇒ new LoggingReceive(source, r)
    }
  }

  object emptyBehavior extends Receive {
    def isDefinedAt(x: Any) = false
    def apply(x: Any) = throw new UnsupportedOperationException("empty behavior apply()")
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
   */
  @transient
  protected[akka] implicit val context: ActorContext = {
    val contextStack = ActorCell.contextStack.get

    def noContextError =
      throw new ActorInitializationException(
        "\n\tYou cannot create an instance of " + getClass.getName + " explicitly using the constructor (new)." +
          "\n\tYou have to use one of the factory methods to create a new actor. Either use:" +
          "\n\t\t'val actor = Actor.actorOf[MyActor]', or" +
          "\n\t\t'val actor = Actor.actorOf(new MyActor(..))'")

    if (contextStack.isEmpty) noContextError
    val context = contextStack.head
    if (context eq null) noContextError
    ActorCell.contextStack.set(contextStack.push(null))
    context
  }

  implicit def app = context.app

  /**
   * The default timeout, based on the config setting 'akka.actor.timeout'
   */
  implicit def defaultTimeout = app.AkkaConfig.ActorTimeout

  /**
   * Wrap a Receive partial function in a logging enclosure, which sends a
   * debug message to the EventHandler each time before a message is matched.
   * This includes messages which are not handled.
   *
   * <pre><code>
   * def receive = loggable {
   *   case x => ...
   * }
   * </code></pre>
   *
   * This method does NOT modify the given Receive unless
   * akka.actor.debug.receive is set within akka.conf.
   */
  def loggable(self: AnyRef)(r: Receive): Receive = if (app.AkkaConfig.AddLoggingReceive) LoggingReceive(self, r) else r //TODO FIXME Shouldn't this be in a Loggable-trait?

  /**
   * Some[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * the 'forward' function.
   */
  def someSelf: Some[ActorRef with ScalaActorRef] = Some(context.self) //TODO FIXME we might not need this when we switch to sender-in-scope-always

  /*
   * Option[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * one of the message send functions ('!' and '?').
   */
  def optionSelf: Option[ActorRef with ScalaActorRef] = someSelf //TODO FIXME we might not need this when we switch to sender-in-scope-always

  /**
   * The 'self' field holds the ActorRef for this actor.
   * <p/>
   * Can be used to send messages to itself:
   * <pre>
   * self ! message
   * </pre>
   */
  implicit def self = someSelf.get

  /**
   * The reference sender Actor of the last received message.
   * Is defined if the message was sent from another Actor, else None.
   */
  @inline
  final def sender: ActorRef = context.sender

  /**
   * Gets the current receive timeout
   * When specified, the receive method should be able to handle a 'ReceiveTimeout' message.
   */
  def receiveTimeout: Option[Long] = context.receiveTimeout

  /**
   * User overridable callback/setting.
   * <p/>
   * Defines the default timeout for an initial receive invocation.
   * When specified, the receive function should be able to handle a 'ReceiveTimeout' message.
   */
  def receiveTimeout_=(timeout: Option[Long]) = context.receiveTimeout = timeout

  /**
   * Same as ActorContext.children
   */
  def children: Iterable[ActorRef] = context.children

  /**
   * Returns the dispatcher (MessageDispatcher) that is used for this Actor
   */
  def dispatcher: MessageDispatcher = context.dispatcher

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
   * Is called when an Actor is started by invoking 'actor'.
   */
  def preStart() {}

  /**
   * User overridable callback.
   * <p/>
   * Is called when 'actor.stop()' is invoked.
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

  /**
   * Changes the Actor's behavior to become the new 'Receive' (PartialFunction[Any, Unit]) handler.
   * Puts the behavior on top of the hotswap stack.
   * If "discardOld" is true, an unbecome will be issued prior to pushing the new behavior to the stack
   */
  def become(behavior: Receive, discardOld: Boolean = true) {
    if (discardOld) unbecome()
    context.hotswap = context.hotswap.push(behavior)
  }

  /**
   * Reverts the Actor behavior to the previous one in the hotswap stack.
   */
  def unbecome() {
    val h = context.hotswap
    if (h.nonEmpty) context.hotswap = h.pop
  }

  /**
   * Registers this actor as a Monitor for the provided ActorRef
   * @return the provided ActorRef
   */
  def watch(subject: ActorRef): ActorRef = self startsMonitoring subject

  /**
   * Unregisters this actor as Monitor for the provided ActorRef
   * @return the provided ActorRef
   */
  def unwatch(subject: ActorRef): ActorRef = self stopsMonitoring subject

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] final def apply(msg: Any) = {

    def autoReceiveMessage(msg: AutoReceivedMessage) {
      if (app.AkkaConfig.DebugAutoReceive) app.eventStream.publish(Debug(this, "received AutoReceiveMessage " + msg))

      msg match {
        case HotSwap(code, discardOld) ⇒ become(code(self), discardOld)
        case RevertHotSwap             ⇒ unbecome()
        case Failed(cause)             ⇒ context.handleFailure(sender, cause)
        case ChildTerminated           ⇒ context.handleChildTerminated(sender)
        case Kill                      ⇒ throw new ActorKilledException("Kill")
        case PoisonPill                ⇒ self.stop()
      }
    }

    if (msg.isInstanceOf[AutoReceivedMessage])
      autoReceiveMessage(msg.asInstanceOf[AutoReceivedMessage])
    else {
      val behaviorStack = context.hotswap
      msg match {
        case msg if behaviorStack.nonEmpty && behaviorStack.head.isDefinedAt(msg) ⇒ behaviorStack.head.apply(msg)
        case msg if behaviorStack.isEmpty && processingBehavior.isDefinedAt(msg) ⇒ processingBehavior.apply(msg)
        case unknown ⇒ unhandled(unknown)
      }
    }
  }

  private val processingBehavior = receive //ProcessingBehavior is the original behavior
}

