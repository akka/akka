/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import akka.util.{ ConstantFun, LineNumbers }
import akka.annotation.InternalApi
import akka.actor.typed.{ WrappingBehavior, ActorContext ⇒ AC }
import akka.actor.typed.scaladsl.{ ActorContext ⇒ SAC }

import scala.reflect.ClassTag
import akka.actor.typed.internal.TimerSchedulerImpl.TimerMsg

/**
 * INTERNAL API
 */
@InternalApi private[akka] object BehaviorImpl {
  import Behavior._

  private[this] final val _any2null = (_: Any) ⇒ null
  private[this] final def any2null[T] = _any2null.asInstanceOf[Any ⇒ T]

  implicit class ContextAs[T](val ctx: AC[T]) extends AnyVal {
    def as[U]: AC[U] = ctx.asInstanceOf[AC[U]]
  }

  def widened[T, U](behavior: Behavior[T], matcher: PartialFunction[U, T]): Behavior[U] = {
    behavior match {
      case d: DeferredBehavior[t] ⇒
        DeferredBehavior[U] { ctx ⇒
          val b = Behavior.validateAsInitial(Behavior.start(d, ctx.as[t]))
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

    override def receive(ctx: AC[U], msg: U): Behavior[U] = {
      // widen would wrap the TimerMessage, which would be wrong, see issue #25318
      msg match {
        case t: TimerMsg ⇒ throw new IllegalArgumentException(
          s"Timers and widen can't be used together, [${t.key}]. See issue #25318")
        case _ ⇒
      }
      matcher.applyOrElse(msg, any2null) match {
        case null        ⇒ unhandled
        case transformed ⇒ widen(Behavior.interpretMessage(behavior, ctx.as[T], transformed), ctx.as[T])
      }
    }

    override def toString: String = s"${behavior.toString}.widen(${LineNumbers(matcher)})"
  }

  class ReceiveBehavior[T](
    val onMessage: (SAC[T], T) ⇒ Behavior[T],
    onSignal:      PartialFunction[(SAC[T], Signal), Behavior[T]] = Behavior.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])
    extends ExtensibleBehavior[T] {

    override def receiveSignal(ctx: AC[T], msg: Signal): Behavior[T] =
      onSignal.applyOrElse((ctx.asScala, msg), Behavior.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])

    override def receive(ctx: AC[T], msg: T) = onMessage(ctx.asScala, msg)

    override def toString = s"Receive(${LineNumbers(onMessage)})"
  }

  /**
   * Similar to [[ReceiveBehavior]] however `onMessage` does not accept context.
   * We implement it separately in order to be able to avoid wrapping each function in
   * another function which drops the context parameter.
   */
  class ReceiveMessageBehavior[T](
    val onMessage: T ⇒ Behavior[T],
    onSignal:      PartialFunction[(SAC[T], Signal), Behavior[T]] = Behavior.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])
    extends ExtensibleBehavior[T] {

    override def receive(ctx: AC[T], msg: T) = onMessage(msg)

    override def receiveSignal(ctx: AC[T], msg: Signal): Behavior[T] =
      onSignal.applyOrElse((ctx.asScala, msg), Behavior.unhandledSignal.asInstanceOf[PartialFunction[(SAC[T], Signal), Behavior[T]]])

    override def toString = s"ReceiveMessage(${LineNumbers(onMessage)})"
  }

  private class Tap extends InterceptId

  def tap[T: ClassTag](
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
      afterMessage = ConstantFun.scalaAnyThreeToThird,
      afterSignal = ConstantFun.scalaAnyThreeToThird,
      behavior,
      new Tap) // FIXME this could cause stack overflow if used recursively
  }

  class InterceptId

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
   *
   * If a behavior returned from the inner behavior processing a message
   * returns a behavior stack that contains the same `interceptId` it will "replace"
   * this intercept to protect against causing stack overflows with nested interceptors.
   * To always keep an interceptor instance, make sure to give it a unique name.
   */
  def intercept[T, U: ClassTag](
    beforeMessage: (SAC[U], U) ⇒ T,
    beforeSignal:  (SAC[T], Signal) ⇒ Boolean,
    afterMessage:  (SAC[T], T, Behavior[T]) ⇒ Behavior[T],
    afterSignal:   (SAC[T], Signal, Behavior[T]) ⇒ Behavior[T],
    behavior:      Behavior[T],
    interceptId:   InterceptId): Behavior[T] = {
    behavior match {
      case d: DeferredBehavior[T] ⇒
        DeferredBehavior[T] { ctx ⇒
          val b = Behavior.validateAsInitial(Behavior.start(d, ctx))
          Intercept(beforeMessage, beforeSignal, afterMessage, afterSignal, b, interceptId)
        }
      case _ ⇒
        val b = Behavior.validateAsInitial(behavior)
        Intercept(beforeMessage, beforeSignal, afterMessage, afterSignal, b, interceptId)
    }
  }

  private[akka] final case class Intercept[T, U <: Any: ClassTag](
    beforeOnMessage: (SAC[U], U) ⇒ T,
    beforeOnSignal:  (SAC[T], Signal) ⇒ Boolean,
    afterMessage:    (SAC[T], T, Behavior[T]) ⇒ Behavior[T],
    afterSignal:     (SAC[T], Signal, Behavior[T]) ⇒ Behavior[T],
    behavior:        Behavior[T],
    interceptId:     InterceptId) extends ExtensibleBehavior[T] with WrappingBehavior[T] {

    def nestedBehavior: Behavior[T] = behavior

    override def replaceNested(newNested: Behavior[T]): Behavior[T] =
      copy(behavior = newNested)

    private def intercept(nextBehavior: Behavior[T], ctx: ActorContext[T]): Behavior[T] = {
      val started = Behavior.start(nextBehavior, ctx)
      val duplicateInterceptExists = Behavior.existsInStack(started) {
        case b: Intercept[_, _] if b.interceptId == interceptId ⇒ true
        case _ ⇒ false
      }

      // don't re-wrap if same interceptor id exists in returned and started behavior stack
      if (duplicateInterceptExists) started
      else Behavior.wrap(this, started, ctx)(Intercept(beforeOnMessage, beforeOnSignal, afterMessage, afterSignal, _, interceptId))
    }

    override def receiveSignal(ctx: AC[T], signal: Signal): Behavior[T] = {
      val next: Behavior[T] =
        if (beforeOnSignal(ctx.asScala, signal))
          Behavior.interpretSignal(behavior, ctx, signal)
        else
          same
      intercept(afterSignal(ctx.asScala, signal, next), ctx)
    }

    override def receive(ctx: AC[T], msg: T): Behavior[T] = {
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

    override def toString = s"$interceptId(${LineNumbers(beforeOnMessage)},${LineNumbers(beforeOnSignal)},$behavior)"

  }

  class OrElseBehavior[T](first: Behavior[T], second: Behavior[T]) extends ExtensibleBehavior[T] {
    override def receive(ctx: AC[T], msg: T): Behavior[T] = {
      Behavior.interpretMessage(first, ctx, msg) match {
        case _: UnhandledBehavior.type ⇒ Behavior.interpretMessage(second, ctx, msg)
        case handled                   ⇒ handled
      }
    }

    override def receiveSignal(ctx: AC[T], msg: Signal): Behavior[T] = {
      Behavior.interpretSignal(first, ctx, msg) match {
        case _: UnhandledBehavior.type ⇒ Behavior.interpretSignal(second, ctx, msg)
        case handled                   ⇒ handled
      }
    }
  }

}
