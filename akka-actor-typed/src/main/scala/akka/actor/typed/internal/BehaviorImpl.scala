/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import akka.util.{ LineNumbers }
import akka.annotation.InternalApi
import akka.actor.typed.{ ActorContext ⇒ AC }
import akka.actor.typed.scaladsl.{ ActorContext ⇒ SAC }

/**
 * INTERNAL API
 */
@InternalApi private[akka] object BehaviorImpl {
  import Behavior._

  implicit class ContextAs[T](val ctx: AC[T]) extends AnyVal {
    def as[U]: AC[U] = ctx.asInstanceOf[AC[U]]
  }

  def widened[O, I](behavior: Behavior[I], matcher: PartialFunction[O, I]): Behavior[O] =
    intercept(WidenedInterceptor(matcher))(behavior)

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

  /**
   * Intercept messages and signals for a `behavior` by first passing them to a [[akka.actor.typed.BehaviorInterceptor]]
   *
   * When a behavior returns a new behavior as a result of processing a signal or message and that behavior already contains
   * the same interceptor (defined by the `isSame` method on the `BehaviorInterceptor`) only the innermost interceptor
   * is kept. This is to protect against stack overflow when recursively defining behaviors.
   */
  def intercept[O, I](interceptor: BehaviorInterceptor[O, I])(behavior: Behavior[I]): Behavior[O] =
    InterceptorImpl(interceptor, behavior)

  class OrElseInterceptor[T](next: Behavior[T]) extends BehaviorInterceptor[T, T] {

    var startedNext: Behavior[T] = _

    override def aroundStart(ctx: AC[T], target: BehaviorInterceptor.PreStartTarget[T]): Behavior[T] = {
      startedNext = Behavior.start(next, ctx)
      target.start(ctx)
    }

    override def aroundReceive(ctx: AC[T], msg: T, target: BehaviorInterceptor.ReceiveTarget[T]): Behavior[T] = {
      target(ctx, msg) match {
        case _: UnhandledBehavior.type ⇒ Behavior.interpretMessage(startedNext, ctx, msg)
        case handled ⇒ handled
      }
    }

    override def aroundSignal(ctx: AC[T], signal: Signal, target: BehaviorInterceptor.SignalTarget[T]): Behavior[T] = {
      target(ctx, signal) match {
        case _: UnhandledBehavior.type ⇒ Behavior.interpretSignal(startedNext, ctx, signal)
        case handled                   ⇒ handled
      }
    }
  }

}
