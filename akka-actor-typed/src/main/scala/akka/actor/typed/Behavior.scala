/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import scala.annotation.switch
import scala.annotation.tailrec

import akka.actor.InvalidMessageException
import akka.actor.typed.internal.BehaviorImpl
import akka.actor.typed.internal.BehaviorImpl.DeferredBehavior
import akka.actor.typed.internal.BehaviorImpl.OrElseBehavior
import akka.actor.typed.internal.BehaviorImpl.StoppedBehavior
import akka.actor.typed.internal.BehaviorTags
import akka.actor.typed.internal.InterceptorImpl
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi

/**
 * The behavior of an actor defines how it reacts to the messages that it
 * receives. The message may either be of the type that the Actor declares
 * and which is part of the [[ActorRef]] signature, or it may be a system
 * [[Signal]] that expresses a lifecycle event of either this actor or one of
 * its child actors.
 *
 * Behaviors can be formulated in a number of different ways, either by
 * using the DSLs in [[akka.actor.typed.scaladsl.Behaviors]] and [[akka.actor.typed.javadsl.Behaviors]]
 * or extending the abstract [[ExtensibleBehavior]] class.
 *
 * Closing over ActorContext makes a Behavior immobile: it cannot be moved to
 * another context and executed there, and therefore it cannot be replicated or
 * forked either.
 *
 * This base class is not meant to be extended by user code. If you do so, you may
 * lose binary compatibility.
 *
 * Not for user extension.
 */
@DoNotInherit
abstract class Behavior[T](private[akka] val _tag: Int) { behavior =>

  /**
   * Narrow the type of this Behavior, which is always a safe operation. This
   * method is necessary to implement the contravariant nature of Behavior
   * (which cannot be expressed directly due to type inference problems).
   */
  final def narrow[U <: T]: Behavior[U] = this.asInstanceOf[Behavior[U]]

  /**
   * INTERNAL API
   *
   * Unsafe utility method for changing the type accepted by this Behavior;
   * provided as an alternative to the universally available `asInstanceOf`, which
   * casts the entire type rather than just the type parameter.
   * Typically used to upcast a type, for instance from `Nothing` to some type `U`.
   * Use it with caution, it may lead to a [[ClassCastException]] when you send a message
   * to the resulting [[Behavior[U]]].
   */
  @InternalApi private[akka] final def unsafeCast[U]: Behavior[U] = this.asInstanceOf[Behavior[U]]

  /**
   * Composes this `Behavior` with a fallback `Behavior` which
   * is used when this `Behavior` doesn't handle the message or signal, i.e.
   * when `unhandled` is returned.
   *
   *  @param that the fallback `Behavior`
   **/
  final def orElse(that: Behavior[T]): Behavior[T] = BehaviorImpl.DeferredBehavior[T] { ctx =>
    new OrElseBehavior[T](Behavior.start(this, ctx), Behavior.start(that, ctx))
  }
}

/**
 * Extension point for implementing custom behaviors in addition to the existing
 * set of behaviors available through the DSLs in [[akka.actor.typed.scaladsl.Behaviors]] and [[akka.actor.typed.javadsl.Behaviors]]
 *
 * Note that behaviors that keep an inner behavior, and intercepts messages for it should not be implemented as
 * an extensible behavior but should instead use the [[BehaviorInterceptor]]
 */
abstract class ExtensibleBehavior[T] extends Behavior[T](BehaviorTags.ExtensibleBehavior) {

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
  def receive(ctx: TypedActorContext[T], msg: T): Behavior[T]

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
  def receiveSignal(ctx: TypedActorContext[T], msg: Signal): Behavior[T]
}

object Behavior {

  final implicit class BehaviorDecorators[T](val behavior: Behavior[T]) extends AnyVal {

    /**
     * Widen the wrapped Behavior by placing a funnel in front of it: the supplied
     * PartialFunction decides which message to pull in (those that it is defined
     * at) and may transform the incoming message to place them into the wrapped
     * Behavior’s type hierarchy. Signals are not transformed.
     *
     * Example:
     * {{{
     * receive[String] { (ctx, msg) => println(msg); same }.widen[Number] {
     *   case b: BigDecimal => s"BigDecimal(&dollar;b)"
     *   case i: BigInteger => s"BigInteger(&dollar;i)"
     *   // all other kinds of Number will be `unhandled`
     * }
     * }}}
     *
     */
    def widen[U](matcher: PartialFunction[U, T]): Behavior[U] =
      BehaviorImpl.widened(behavior, matcher)

  }

  /**
   * Given a possibly special behavior (same or unhandled) and a
   * “current” behavior (which defines the meaning of encountering a `same`
   * behavior) this method computes the next behavior, suitable for passing a
   * message or signal.
   */
  @tailrec
  def canonicalize[T](behavior: Behavior[T], current: Behavior[T], ctx: TypedActorContext[T]): Behavior[T] =
    (behavior._tag: @switch) match {
      case BehaviorTags.SameBehavior      => current
      case BehaviorTags.UnhandledBehavior => current
      case BehaviorTags.DeferredBehavior =>
        val deferred = behavior.asInstanceOf[DeferredBehavior[T]]
        canonicalize(deferred(ctx), deferred, ctx)
      case _ => behavior
    }

  /**
   * Starts deferred behavior and nested deferred behaviors until all deferred behaviors in the stack are started
   * and then the resulting behavior is returned.
   */
  def start[T](behavior: Behavior[T], ctx: TypedActorContext[T]): Behavior[T] = {
    // TODO can this be made @tailrec?
    behavior match {
      case innerDeferred: DeferredBehavior[T]          => start(innerDeferred(ctx), ctx)
      case wrapped: InterceptorImpl[T, Any] @unchecked =>
        // make sure that a deferred behavior wrapped inside some other behavior is also started
        val startedInner = start(wrapped.nestedBehavior, ctx.asInstanceOf[TypedActorContext[Any]])
        if (startedInner eq wrapped.nestedBehavior) wrapped
        else wrapped.replaceNested(startedInner)
      case _ => behavior
    }
  }

  /**
   * Go through the behavior stack and apply a predicate to see if any nested behavior
   * satisfies it. The stack must not contain any unstarted deferred behavior or an `IllegalArgumentException`
   * will be thrown.
   */
  def existsInStack[T](behavior: Behavior[T])(p: Behavior[T] => Boolean): Boolean = {
    @tailrec
    def loop(b: Behavior[T]): Boolean =
      b match {
        case _ if p(b) => true
        case wrappingBehavior: InterceptorImpl[T, T] @unchecked =>
          loop(wrappingBehavior.nestedBehavior)
        case d: DeferredBehavior[T] =>
          throw new IllegalArgumentException(
            "Cannot verify behavior existence when there are deferred in the behavior stack, " +
            s"Behavior.start the stack first. This is probably a bug, please create an issue. $d")
        case _ => false
      }

    loop(behavior)
  }

  /**
   * Validate the given behavior as a suitable initial actor behavior; most
   * notably the behavior can neither be `same` nor `unhandled`. Starting
   * out with a `Stopped` behavior is allowed, though.
   */
  def validateAsInitial[T](behavior: Behavior[T]): Behavior[T] =
    if (behavior._tag == BehaviorTags.SameBehavior || behavior._tag == BehaviorTags.UnhandledBehavior)
      throw new IllegalArgumentException(s"cannot use $behavior as initial behavior")
    else behavior

  /**
   * Returns true if the given behavior is not stopped.
   */
  def isAlive[T](behavior: Behavior[T]): Boolean =
    !(behavior._tag == BehaviorTags.StoppedBehavior || behavior._tag == BehaviorTags.FailedBehavior)

  /**
   * Returns true if the given behavior is the special `unhandled` marker.
   */
  def isUnhandled[T](behavior: Behavior[T]): Boolean = behavior eq BehaviorImpl.UnhandledBehavior

  /**
   * Returns true if the given behavior is the special `Unhandled` marker.
   */
  def isDeferred[T](behavior: Behavior[T]): Boolean = behavior._tag == BehaviorTags.DeferredBehavior

  /**
   * Execute the behavior with the given message
   */
  def interpretMessage[T](behavior: Behavior[T], ctx: TypedActorContext[T], msg: T): Behavior[T] =
    interpret(behavior, ctx, msg, isSignal = false)

  /**
   * Execute the behavior with the given signal
   */
  def interpretSignal[T](behavior: Behavior[T], ctx: TypedActorContext[T], signal: Signal): Behavior[T] = {
    val result = interpret(behavior, ctx, signal, isSignal = true)
    // we need to throw here to allow supervision of deathpact exception
    signal match {
      case Terminated(ref) if result == BehaviorImpl.UnhandledBehavior => throw DeathPactException(ref)
      case _                                                           => result
    }
  }

  private def interpret[T](
      behavior: Behavior[T],
      ctx: TypedActorContext[T],
      msg: Any,
      // optimization to avoid an instanceof on the message
      isSignal: Boolean): Behavior[T] = {
    if (behavior eq null)
      throw InvalidMessageException("[null] is not an allowed behavior")

    (behavior._tag: @switch) match {
      case BehaviorTags.SameBehavior =>
        throw new IllegalArgumentException(s"cannot execute with [$behavior] as behavior")
      case BehaviorTags.UnhandledBehavior =>
        throw new IllegalArgumentException(s"cannot execute with [$behavior] as behavior")
      case BehaviorTags.DeferredBehavior =>
        throw new IllegalArgumentException(s"deferred [$behavior] should not be passed to interpreter")
      case BehaviorTags.IgnoreBehavior =>
        BehaviorImpl.same[T]
      case BehaviorTags.StoppedBehavior =>
        val s = behavior.asInstanceOf[StoppedBehavior[T]]
        if (msg == PostStop) s.onPostStop(ctx)
        s
      case BehaviorTags.FailedBehavior =>
        behavior
      case BehaviorTags.EmptyBehavior =>
        BehaviorImpl.unhandled[T]
      case BehaviorTags.ExtensibleBehavior =>
        val ext = behavior.asInstanceOf[ExtensibleBehavior[T]]
        val possiblyDeferredResult =
          if (isSignal) ext.receiveSignal(ctx, msg.asInstanceOf[Signal])
          else ext.receive(ctx, msg.asInstanceOf[T])
        start(possiblyDeferredResult, ctx)
    }
  }

}
