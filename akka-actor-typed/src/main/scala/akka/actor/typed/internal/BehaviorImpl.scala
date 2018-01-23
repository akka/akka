/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed
package internal

import akka.util.LineNumbers
import akka.annotation.InternalApi
import akka.actor.typed.{ ActorContext ⇒ AC }
import akka.actor.typed.scaladsl.{ ActorContext ⇒ SAC }

import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] object BehaviorImpl {
  import Behavior._

  private val _nullFun = (_: Any) ⇒ null
  private def nullFun[T] = _nullFun.asInstanceOf[Any ⇒ T]

  implicit class ContextAs[T](val ctx: AC[T]) extends AnyVal {
    def as[U]: AC[U] = ctx.asInstanceOf[AC[U]]
  }

  def widened[T, U](behavior: Behavior[T], matcher: PartialFunction[U, T]): Behavior[U] = {
    behavior match {
      case d: DeferredBehavior[T] ⇒
        DeferredBehavior[U] { ctx ⇒
          val c = ctx.asInstanceOf[akka.actor.typed.ActorContext[T]]
          val b = Behavior.validateAsInitial(Behavior.start(d, c))
          Widened(b, matcher)
        }
      case _ ⇒
        Widened(behavior, matcher)
    }
  }

  private final case class Widened[T, U](behavior: Behavior[T], matcher: PartialFunction[U, T]) extends ExtensibleBehavior[U] {

    private def widen(b: Behavior[T], ctx: AC[T]): Behavior[U] =
      Behavior.wrap(this, b, ctx)(b ⇒ Widened[T, U](b, matcher))

    override def receiveSignal(ctx: AC[U], signal: Signal): Behavior[U] =
      widen(Behavior.interpretSignal(behavior, ctx.as[T], signal), ctx.as[T])

    override def receiveMessage(ctx: AC[U], msg: U): Behavior[U] =
      matcher.applyOrElse(msg, nullFun) match {
        case null        ⇒ unhandled
        case transformed ⇒ widen(Behavior.interpretMessage(behavior, ctx.as[T], transformed), ctx.as[T])
      }

    override def toString: String = s"${behavior.toString}.widen(${LineNumbers(matcher)})"
  }

  class ImmutableBehavior[T](
    val onMessage: (SAC[T], T) ⇒ Behavior[T],
    onSignal:      PartialFunction[(SAC[T], Signal), Behavior[T]] = Behavior.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])
    extends ExtensibleBehavior[T] {

    override def receiveSignal(ctx: AC[T], msg: Signal): Behavior[T] =
      onSignal.applyOrElse((ctx.asScala, msg), Behavior.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])

    override def receiveMessage(ctx: AC[T], msg: T) = onMessage(ctx.asScala, msg)

    override def toString = s"Immutable(${LineNumbers(onMessage)})"
  }

  def tap[T](
    onMessage: (SAC[T], T) ⇒ _,
    onSignal:  (SAC[T], Signal) ⇒ _,
    behavior:  Behavior[T]): Behavior[T] = {
    intercept[T, T](
      beforeMessage = (ctx, msg) ⇒ {
        onMessage(ctx, msg)
        msg
      },
      beforeSignal = (ctx, sig) ⇒ {
        onSignal(ctx, sig)
        true
      },
      afterMessage = (_, _, b) ⇒ b, // TODO optimize by using more ConstantFun
      afterSignal = (_, _, b) ⇒ b,
      behavior)(ClassTag(classOf[Any]))
  }

  /**
   * Intercept another `behavior` by invoking `beforeMessage` for
   * messages of type `U`. That can be another type than the type of
   * the behavior. `beforeMessage` may transform the incoming message,
   * or discard it by returning `null`. Note that `beforeMessage` is
   * only invoked for messages of type `U`.
   *
   * Signals can also be intercepted but not transformed. They can
   * be discarded by returning `false` from the `beforeOnSignal` function.
   *
   * The returned behavior from processing messages and signals can also be
   * intercepted, e.g. to return another `Behavior`. The passed message to
   * `afterMessage` is the message returned from `beforeMessage` (possibly
   * different than the incoming message).
   */
  def intercept[T, U <: Any: ClassTag](
    beforeMessage:  (SAC[U], U) ⇒ T,
    beforeSignal:   (SAC[T], Signal) ⇒ Boolean,
    afterMessage:   (SAC[T], T, Behavior[T]) ⇒ Behavior[T],
    afterSignal:    (SAC[T], Signal, Behavior[T]) ⇒ Behavior[T],
    behavior:       Behavior[T],
    toStringPrefix: String                                      = "Intercept"): Behavior[T] = {
    behavior match {
      case d: DeferredBehavior[T] ⇒
        DeferredBehavior[T] { ctx ⇒
          val c = ctx.asInstanceOf[akka.actor.typed.ActorContext[T]]
          val b = Behavior.validateAsInitial(Behavior.start(d, c))
          Intercept(beforeMessage, beforeSignal, afterMessage, afterSignal, b, toStringPrefix)
        }
      case _ ⇒
        Intercept(beforeMessage, beforeSignal, afterMessage, afterSignal, behavior, toStringPrefix)
    }
  }

  private final case class Intercept[T, U <: Any: ClassTag](
    beforeOnMessage: (SAC[U], U) ⇒ T,
    beforeOnSignal:  (SAC[T], Signal) ⇒ Boolean,
    afterMessage:    (SAC[T], T, Behavior[T]) ⇒ Behavior[T],
    afterSignal:     (SAC[T], Signal, Behavior[T]) ⇒ Behavior[T],
    behavior:        Behavior[T],
    toStringPrefix:  String                                      = "Intercept") extends ExtensibleBehavior[T] {

    private def intercept(nextBehavior: Behavior[T], ctx: ActorContext[T]): Behavior[T] = {
      Behavior.wrap(this, nextBehavior, ctx)(Intercept(beforeOnMessage, beforeOnSignal, afterMessage, afterSignal, _))
    }

    override def receiveSignal(ctx: AC[T], signal: Signal): Behavior[T] = {
      val next: Behavior[T] =
        if (beforeOnSignal(ctx.asScala, signal))
          Behavior.interpretSignal(behavior, ctx, signal)
        else
          same
      intercept(afterSignal(ctx.asScala, signal, next), ctx)
    }

    override def receiveMessage(ctx: AC[T], msg: T): Behavior[T] = {
      msg match {
        case m: U ⇒
          val msg2 = beforeOnMessage(ctx.asScala.asInstanceOf[SAC[U]], m)
          val next: Behavior[T] =
            if (msg2 == null)
              same
            else
              Behavior.interpretMessage(behavior, ctx, msg2)
          intercept(afterMessage(ctx.asScala, msg2, next), ctx)
        case _ ⇒
          val next: Behavior[T] = Behavior.interpretMessage(behavior, ctx, msg)
          intercept(afterMessage(ctx.asScala, msg, next), ctx)
      }
    }

    override def toString = s"$toStringPrefix(${LineNumbers(beforeOnMessage)},${LineNumbers(beforeOnSignal)},$behavior)"
  }

}
