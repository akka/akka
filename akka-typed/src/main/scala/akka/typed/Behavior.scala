/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.annotation.tailrec
import akka.util.LineNumbers
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.typed.scaladsl.{ ActorContext ⇒ SAC }
import akka.util.OptionVal

/**
 * The behavior of an actor defines how it reacts to the messages that it
 * receives. The message may either be of the type that the Actor declares
 * and which is part of the [[ActorRef]] signature, or it may be a system
 * [[Signal]] that expresses a lifecycle event of either this actor or one of
 * its child actors.
 *
 * Behaviors can be formulated in a number of different ways, either by
 * using the DSLs in [[akka.typed.scaladsl.Actor]] and [[akka.typed.javadsl.Actor]]
 * or extending the abstract [[ExtensibleBehavior]] class.
 *
 * Closing over ActorContext makes a Behavior immobile: it cannot be moved to
 * another context and executed there, and therefore it cannot be replicated or
 * forked either.
 *
 * This base class is not meant to be extended by user code. If you do so, you may
 * lose binary compatibility.
 */
@InternalApi
@DoNotInherit
sealed abstract class Behavior[T] {
  /**
   * Narrow the type of this Behavior, which is always a safe operation. This
   * method is necessary to implement the contravariant nature of Behavior
   * (which cannot be expressed directly due to type inference problems).
   */
  final def narrow[U <: T]: Behavior[U] = this.asInstanceOf[Behavior[U]]
}

/**
 * Extension point for implementing custom behaviors in addition to the existing
 * set of behaviors available through the DSLs in [[akka.typed.scaladsl.Actor]] and [[akka.typed.javadsl.Actor]]
 */
abstract class ExtensibleBehavior[T] extends Behavior[T] {
  /**
   * Process an incoming [[Signal]] and return the next behavior. This means
   * that all lifecycle hooks, ReceiveTimeout, Terminated and Failed messages
   * can initiate a behavior change.
   *
   * The returned behavior can in addition to normal behaviors be one of the
   * canned special objects:
   *
   *  * returning `stopped` will terminate this Behavior
   *  * returning `same` designates to reuse the current Behavior
   *  * returning `unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * Code calling this method should use [[Behavior$]] `canonicalize` to replace
   * the special objects with real Behaviors.
   */
  @throws(classOf[Exception])
  def receiveSignal(ctx: ActorContext[T], msg: Signal): Behavior[T]

  /**
   * Process an incoming message and return the next behavior.
   *
   * The returned behavior can in addition to normal behaviors be one of the
   * canned special objects:
   *
   *  * returning `stopped` will terminate this Behavior
   *  * returning `same` designates to reuse the current Behavior
   *  * returning `unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * Code calling this method should use [[Behavior$]] `canonicalize` to replace
   * the special objects with real Behaviors.
   */
  @throws(classOf[Exception])
  def receiveMessage(ctx: ActorContext[T], msg: T): Behavior[T]

}

object Behavior {

  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior. This is provided in order to
   * avoid the allocation overhead of recreating the current behavior where
   * that is not necessary.
   */
  def same[T]: Behavior[T] = SameBehavior.asInstanceOf[Behavior[T]]
  /**
   * Return this behavior from message processing in order to advise the
   * system to reuse the previous behavior, including the hint that the
   * message has not been handled. This hint may be used by composite
   * behaviors that delegate (partial) handling to other behaviors.
   */
  def unhandled[T]: Behavior[T] = UnhandledBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure.
   *
   * The PostStop signal that results from stopping this actor will be passed to the
   * current behavior. All other messages and signals will effectively be
   * ignored.
   */
  def stopped[T]: Behavior[T] = StoppedBehavior.asInstanceOf[Behavior[T]]

  /**
   * Return this behavior from message processing to signal that this actor
   * shall terminate voluntarily. If this actor has created child actors then
   * these will be stopped as part of the shutdown procedure.
   *
   * The PostStop signal that results from stopping this actor will be passed to the
   * given `postStop` behavior. All other messages and signals will effectively be
   * ignored.
   */
  def stopped[T](postStop: Behavior[T]): Behavior[T] =
    new StoppedBehavior(OptionVal.Some(postStop))

  /**
   * A behavior that treats every incoming message as unhandled.
   */
  def empty[T]: Behavior[T] = EmptyBehavior.asInstanceOf[Behavior[T]]

  /**
   * A behavior that ignores every incoming message and returns “same”.
   */
  def ignore[T]: Behavior[T] = IgnoreBehavior.asInstanceOf[Behavior[T]]

  /**
   * INTERNAL API.
   */
  @InternalApi
  private[akka] object EmptyBehavior extends Behavior[Any] {
    override def toString = "Empty"
  }

  /**
   * INTERNAL API.
   */
  @InternalApi
  private[akka] object IgnoreBehavior extends Behavior[Any] {
    override def toString = "Ignore"
  }

  /**
   * INTERNAL API.
   */
  @InternalApi
  private[akka] object UnhandledBehavior extends Behavior[Nothing] {
    override def toString = "Unhandled"
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val unhandledSignal: PartialFunction[(ActorContext[Nothing], Signal), Behavior[Nothing]] = {
    case (_, _) ⇒ UnhandledBehavior
  }

  /**
   * INTERNAL API.
   * Not placed in internal.BehaviorImpl because Behavior is sealed.
   */
  @InternalApi
  private[akka] final case class DeferredBehavior[T](factory: SAC[T] ⇒ Behavior[T]) extends Behavior[T] {

    /** "undefer" the deferred behavior */
    @throws(classOf[Exception])
    def apply(ctx: ActorContext[T]): Behavior[T] = factory(ctx.asScala)

    override def toString: String = s"Deferred(${LineNumbers(factory)})"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object SameBehavior extends Behavior[Nothing] {
    override def toString = "Same"
  }

  /**
   * INTERNAL API.
   */
  private[akka] object StoppedBehavior extends StoppedBehavior[Nothing](OptionVal.None)

  /**
   * INTERNAL API: When the cell is stopping this behavior is used, so
   * that PostStop can be sent to previous behavior from `finishTerminate`.
   */
  private[akka] class StoppedBehavior[T](val postStop: OptionVal[Behavior[T]]) extends Behavior[T] {
    override def toString = "Stopped"
  }

  /**
   * Given a possibly special behavior (same or unhandled) and a
   * “current” behavior (which defines the meaning of encountering a `same`
   * behavior) this method computes the next behavior, suitable for passing a
   * message or signal.
   */
  @tailrec
  def canonicalize[T](behavior: Behavior[T], current: Behavior[T], ctx: ActorContext[T]): Behavior[T] =
    behavior match {
      case SameBehavior                  ⇒ current
      case UnhandledBehavior             ⇒ current
      case deferred: DeferredBehavior[T] ⇒ canonicalize(deferred(ctx), deferred, ctx)
      case other                         ⇒ other
    }

  @tailrec
  def undefer[T](behavior: Behavior[T], ctx: ActorContext[T]): Behavior[T] = {
    behavior match {
      case innerDeferred: DeferredBehavior[T] ⇒ undefer(innerDeferred(ctx), ctx)
      case _                                  ⇒ behavior
    }
  }

  /**
   * Validate the given behavior as a suitable initial actor behavior; most
   * notably the behavior can neither be `same` nor `unhandled`. Starting
   * out with a `Stopped` behavior is allowed, though.
   */
  def validateAsInitial[T](behavior: Behavior[T]): Behavior[T] =
    behavior match {
      case SameBehavior | UnhandledBehavior ⇒
        throw new IllegalArgumentException(s"cannot use $behavior as initial behavior")
      case x ⇒ x
    }

  /**
   * Returns true if the given behavior is not stopped.
   */
  def isAlive[T](behavior: Behavior[T]): Boolean = behavior match {
    case _: StoppedBehavior[_] ⇒ false
    case _                     ⇒ true
  }

  /**
   * Returns true if the given behavior is the special `unhandled` marker.
   */
  def isUnhandled[T](behavior: Behavior[T]): Boolean = behavior eq UnhandledBehavior

  /**
   * Returns true if the given behavior is the special `Unhandled` marker.
   */
  def isDeferred[T](behavior: Behavior[T]): Boolean = behavior match {
    case _: DeferredBehavior[T] ⇒ true
    case _                      ⇒ false
  }

  /**
   * Execute the behavior with the given message
   */
  def interpretMessage[T](behavior: Behavior[T], ctx: ActorContext[T], msg: T): Behavior[T] =
    interpret(behavior, ctx, msg)

  /**
   * Execute the behavior with the given signal
   */
  def interpretSignal[T](behavior: Behavior[T], ctx: ActorContext[T], signal: Signal): Behavior[T] =
    interpret(behavior, ctx, signal)

  private def interpret[T](behavior: Behavior[T], ctx: ActorContext[T], msg: Any): Behavior[T] =
    behavior match {
      case SameBehavior | UnhandledBehavior ⇒ throw new IllegalArgumentException(s"cannot execute with [$behavior] as behavior")
      case d: DeferredBehavior[_]           ⇒ throw new IllegalArgumentException(s"deferred [$d] should not be passed to interpreter")
      case IgnoreBehavior                   ⇒ SameBehavior.asInstanceOf[Behavior[T]]
      case s: StoppedBehavior[T]            ⇒ s
      case EmptyBehavior                    ⇒ UnhandledBehavior.asInstanceOf[Behavior[T]]
      case ext: ExtensibleBehavior[T] ⇒
        val possiblyDeferredResult = msg match {
          case signal: Signal ⇒ ext.receiveSignal(ctx, signal)
          case msg            ⇒ ext.receiveMessage(ctx, msg.asInstanceOf[T])
        }
        undefer(possiblyDeferredResult, ctx)
    }

}
