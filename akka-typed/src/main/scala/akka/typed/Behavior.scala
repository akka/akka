/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.annotation.{ DoNotInherit, InternalApi }

import scala.annotation.tailrec

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
   *  * returning `Stopped` will terminate this Behavior
   *  * returning `Same` designates to reuse the current Behavior
   *  * returning `Unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * Code calling this method should use [[Behavior$]] `canonicalize` to replace
   * the special objects with real Behaviors.
   */
  @throws(classOf[Exception])
  def management(ctx: ActorContext[T], msg: Signal): Behavior[T]

  /**
   * Process an incoming message and return the next behavior.
   *
   * The returned behavior can in addition to normal behaviors be one of the
   * canned special objects:
   *
   *  * returning `Stopped` will terminate this Behavior
   *  * returning `Same` designates to reuse the current Behavior
   *  * returning `Unhandled` keeps the same Behavior and signals that the message was not yet handled
   *
   * Code calling this method should use [[Behavior$]] `canonicalize` to replace
   * the special objects with real Behaviors.
   */
  @throws(classOf[Exception])
  def message(ctx: ActorContext[T], msg: T): Behavior[T]

}

object Behavior {

  /**
   * INTERNAL API.
   */
  @InternalApi
  @SerialVersionUID(1L)
  private[akka] object EmptyBehavior extends Behavior[Any] {
    override def toString = "Empty"
  }

  /**
   * INTERNAL API.
   */
  @InternalApi
  @SerialVersionUID(1L)
  private[akka] object IgnoreBehavior extends Behavior[Any] {
    override def toString = "Ignore"
  }

  /**
   * INTERNAL API.
   */
  @InternalApi
  @SerialVersionUID(1L)
  private[akka] object UnhandledBehavior extends Behavior[Nothing] {
    override def toString = "Unhandled"
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val unhandledSignal: (ActorContext[Nothing], Signal) ⇒ Behavior[Nothing] =
    (_, _) ⇒ UnhandledBehavior

  /**
   * INTERNAL API.
   */
  @InternalApi
  @DoNotInherit
  @SerialVersionUID(1L)
  private[akka] abstract class DeferredBehavior[T] extends Behavior[T] {
    /** "undefer" the deferred behavior */
    @throws(classOf[Exception])
    def apply(ctx: ActorContext[T]): Behavior[T]
  }

  /**
   * INTERNAL API.
   */
  @SerialVersionUID(1L)
  private[akka] object SameBehavior extends Behavior[Nothing] {
    override def toString = "Same"
  }

  /**
   * INTERNAL API.
   */
  @SerialVersionUID(1L)
  private[akka] object StoppedBehavior extends Behavior[Nothing] {
    override def toString = "Stopped"
  }

  /**
   * Given a possibly special behavior (same or unhandled) and a
   * “current” behavior (which defines the meaning of encountering a `Same`
   * behavior) this method computes the next behavior, suitable for passing a
   * message or signal.
   */
  def canonicalize[T](behavior: Behavior[T], current: Behavior[T], ctx: ActorContext[T]): Behavior[T] =
    behavior match {
      case SameBehavior                  ⇒ current
      case UnhandledBehavior             ⇒ current
      case deferred: DeferredBehavior[T] ⇒ canonicalize(undefer(deferred, ctx), deferred, ctx)
      case other                         ⇒ other
    }

  @tailrec
  def undefer[T](behavior: Behavior[T], ctx: ActorContext[T]): Behavior[T] = behavior match {
    case innerDeferred: DeferredBehavior[T] @unchecked ⇒ undefer(innerDeferred(ctx), ctx)
    case _ ⇒ behavior
  }

  /**
   * Validate the given behavior as a suitable initial actor behavior; most
   * notably the behavior can neither be `Same` nor `Unhandled`. Starting
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
  def isAlive[T](behavior: Behavior[T]): Boolean = behavior ne StoppedBehavior

  /**
   * Returns true if the given behavior is the special `Unhandled` marker.
   */
  def isUnhandled[T](behavior: Behavior[T]): Boolean = behavior eq UnhandledBehavior

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
      case StoppedBehavior                  ⇒ StoppedBehavior.asInstanceOf[Behavior[T]]
      case EmptyBehavior                    ⇒ UnhandledBehavior.asInstanceOf[Behavior[T]]
      case ext: ExtensibleBehavior[T] @unchecked ⇒
        val possiblyDeferredResult = msg match {
          case signal: Signal ⇒ ext.management(ctx, signal)
          case msg            ⇒ ext.message(ctx, msg.asInstanceOf[T])
        }
        undefer(possiblyDeferredResult, ctx)
    }

}
