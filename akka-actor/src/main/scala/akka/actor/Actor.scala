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
import akka.event.EventHandler
import akka.experimental
import akka.{ AkkaApplication, AkkaException }

import scala.reflect.BeanProperty

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

case class Failed(@BeanProperty actor: ActorRef,
                  @BeanProperty cause: Throwable) extends AutoReceivedMessage with PossiblyHarmful

case class ChildTerminated(@BeanProperty child: ActorRef, @BeanProperty cause: Throwable) extends AutoReceivedMessage with PossiblyHarmful

case object RevertHotSwap extends AutoReceivedMessage with PossiblyHarmful

case object PoisonPill extends AutoReceivedMessage with PossiblyHarmful

case object Kill extends AutoReceivedMessage with PossiblyHarmful

case object ReceiveTimeout extends PossiblyHarmful

case class Terminated(@BeanProperty actor: ActorRef, @BeanProperty cause: Throwable)

// Exceptions for Actors
class ActorStartException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

class IllegalActorStateException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

class ActorKilledException private[akka] (message: String, cause: Throwable) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

class ActorInitializationException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

class ActorTimeoutException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

class InvalidMessageException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

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
 */
object Status { //FIXME Why does this exist at all?
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
  implicit def defaultTimeout(implicit application: AkkaApplication) = application.AkkaConfig.ActorTimeout
}

object Actor {

  type Receive = PartialFunction[Any, Unit]

  /**
   * This decorator adds invocation logging to a Receive function.
   */
  class LoggingReceive(source: AnyRef, r: Receive) extends Receive {
    def isDefinedAt(o: Any) = {
      val handled = r.isDefinedAt(o)
      EventHandler.debug(source, "received " + (if (handled) "handled" else "unhandled") + " message " + o)
      handled
    }
    def apply(o: Any): Unit = r(o)
  }

  object LoggingReceive {
    def apply(source: AnyRef, r: Receive): Receive = r match {
      case _: LoggingReceive ⇒ r
      case _                 ⇒ new LoggingReceive(source, r)
    }
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
  private[akka] val context: ActorContext = {
    val contextStack = ActorCell.contextStack.get

    def noContextError = {
      throw new ActorInitializationException(
        "\n\tYou cannot create an instance of " + getClass.getName + " explicitly using the constructor (new)." +
          "\n\tYou have to use one of the factory methods to create a new actor. Either use:" +
          "\n\t\t'val actor = Actor.actorOf[MyActor]', or" +
          "\n\t\t'val actor = Actor.actorOf(new MyActor(..))'")
    }

    if (contextStack.isEmpty) noContextError
    val context = contextStack.head
    if (context eq null) noContextError
    ActorCell.contextStack.set(contextStack.push(null))
    context
  }

  implicit def app = context.application

  private def config = context.application.AkkaConfig

  /**
   * The default timeout, based on the config setting 'akka.actor.timeout'
   */
  implicit val defaultTimeout = config.ActorTimeout

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
  def loggable(self: AnyRef)(r: Receive): Receive = if (config.AddLoggingReceive) LoggingReceive(self, r) else r

  /**
   * Some[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * the 'forward' function.
   */
  def someSelf: Some[ActorRef with ScalaActorRef] = Some(context.self)

  /*
   * Option[ActorRef] representation of the 'self' ActorRef reference.
   * <p/>
   * Mainly for internal use, functions as the implicit sender references when invoking
   * one of the message send functions ('!' and '?').
   */
  def optionSelf: Option[ActorRef with ScalaActorRef] = someSelf

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
  def sender: Option[ActorRef] = context.sender

  /**
   * The reference sender future of the last received message.
   * Is defined if the message was sent with sent with '?'/'ask', else None.
   */
  def senderFuture(): Option[Promise[Any]] = context.senderFuture

  /**
   * Abstraction for unification of sender and senderFuture for later reply
   */
  def channel: UntypedChannel = context.channel

  // just for current compatibility
  implicit def forwardable: ForwardableChannel = ForwardableChannel(channel)

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
   * Akka Scala & Java API
   * Use <code>reply(..)</code> to reply with a message to the original sender of the message currently
   * being processed. This method fails if the original sender of the message could not be determined with an
   * IllegalStateException.
   *
   * If you don't want deal with this IllegalStateException, but just a boolean, just use the <code>tryReply(...)</code>
   * version.
   *
   * <p/>
   * Throws an IllegalStateException if unable to determine what to reply to.
   */
  def reply(message: Any) = channel.!(message)(self)

  /**
   * Akka Scala & Java API
   * Use <code>tryReply(..)</code> to try reply with a message to the original sender of the message currently
   * being processed. This method
   * <p/>
   * Returns true if reply was sent, and false if unable to determine what to reply to.
   *
   * If you would rather have an exception, check the <code>reply(..)</code> version.
   */
  def tryReply(message: Any): Boolean = channel.tryTell(message)(self)

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
   *       reply("pong")
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
    //EventHandler.warning(self, message)
    throw new UnhandledMessageException(message, self)
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

  // =========================================
  // ==== INTERNAL IMPLEMENTATION DETAILS ====
  // =========================================

  private[akka] final def apply(msg: Any) = {
    if (msg.isInstanceOf[AnyRef] && (msg.asInstanceOf[AnyRef] eq null))
      throw new InvalidMessageException("Message from [" + channel + "] to [" + self.toString + "] is null")

    def autoReceiveMessage(msg: AutoReceivedMessage) {
      if (config.DebugAutoReceive) EventHandler.debug(this, "received AutoReceiveMessage " + msg)

      msg match {
        case HotSwap(code, discardOld) ⇒ become(code(self), discardOld)
        case RevertHotSwap             ⇒ unbecome()
        case f: Failed                 ⇒ context.handleFailure(f)
        case ct: ChildTerminated       ⇒ context.handleChildTerminated(ct.child)
        case Kill                      ⇒ throw new ActorKilledException("Kill")
        case PoisonPill ⇒
          val ch = channel
          self.stop()
          ch.sendException(new ActorKilledException("PoisonPill"))
      }
    }

    if (msg.isInstanceOf[AutoReceivedMessage])
      autoReceiveMessage(msg.asInstanceOf[AutoReceivedMessage])
    else {
      val behaviorStack = context.hotswap
      msg match {
        case msg if behaviorStack.nonEmpty && behaviorStack.head.isDefinedAt(msg) ⇒ behaviorStack.head.apply(msg)
        case msg if behaviorStack.isEmpty && processingBehavior.isDefinedAt(msg) ⇒ processingBehavior.apply(msg)
        case unknown ⇒ unhandled(unknown) //This is the only line that differs from processingbehavior
      }
    }
  }

  private lazy val processingBehavior = receive //ProcessingBehavior is the original behavior
}

/**
 * Helper methods and fields for working with actor addresses.
 * Meant for internal use.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Address {

  val clusterActorRefPrefix = "cluster-actor-ref.".intern

  private val validAddressPattern = java.util.regex.Pattern.compile("[0-9a-zA-Z\\-\\_\\$\\.]+")

  def validate(address: String) {
    if (!validAddressPattern.matcher(address).matches) {
      val e = new IllegalArgumentException("Address [" + address + "] is not valid, need to follow pattern: " + validAddressPattern.pattern)
      EventHandler.error(e, this, e.getMessage)
      throw e
    }
  }
}

