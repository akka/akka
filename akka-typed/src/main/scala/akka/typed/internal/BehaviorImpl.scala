/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed
package internal

import akka.util.LineNumbers
import akka.annotation.InternalApi
import akka.typed.{ ActorContext ⇒ AC }
import akka.typed.scaladsl.{ ActorContext ⇒ SAC }
import akka.typed.scaladsl.Actor

/**
 * INTERNAL API
 */
@InternalApi private[akka] object BehaviorImpl {
  import Behavior._

  private val _nullFun = (_: Any) ⇒ null
  private def nullFun[T] = _nullFun.asInstanceOf[Any ⇒ T]

  implicit class ContextAs[T](val ctx: AC[T]) extends AnyVal {
    def as[U] = ctx.asInstanceOf[AC[U]]
  }

  final case class Widened[T, U](behavior: Behavior[T], matcher: PartialFunction[U, T]) extends ExtensibleBehavior[U] {
    private def postProcess(behv: Behavior[T], ctx: AC[T]): Behavior[U] =
      if (isUnhandled(behv)) unhandled
      else if (isAlive(behv)) {
        val next = canonicalize(behv, behavior, ctx)
        if (next eq behavior) same else Widened(next, matcher)
      } else stopped

    override def receiveSignal(ctx: AC[U], signal: Signal): Behavior[U] =
      postProcess(Behavior.interpretSignal(behavior, ctx.as[T], signal), ctx.as[T])

    override def receiveMessage(ctx: AC[U], msg: U): Behavior[U] =
      matcher.applyOrElse(msg, nullFun) match {
        case null        ⇒ unhandled
        case transformed ⇒ postProcess(Behavior.interpretMessage(behavior, ctx.as[T], transformed), ctx.as[T])
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

  final case class Tap[T](
    onMessage: Function2[SAC[T], T, _],
    onSignal:  Function2[SAC[T], Signal, _],
    behavior:  Behavior[T]) extends ExtensibleBehavior[T] {

    private def canonical(behv: Behavior[T]): Behavior[T] =
      if (isUnhandled(behv)) unhandled
      else if ((behv eq SameBehavior) || (behv eq this)) same
      else if (isAlive(behv)) Tap(onMessage, onSignal, behv)
      else stopped
    override def receiveSignal(ctx: AC[T], signal: Signal): Behavior[T] = {
      onSignal(ctx.asScala, signal)
      canonical(Behavior.interpretSignal(behavior, ctx, signal))
    }
    override def receiveMessage(ctx: AC[T], msg: T): Behavior[T] = {
      onMessage(ctx.asScala, msg)
      canonical(Behavior.interpretMessage(behavior, ctx, msg))
    }
    override def toString = s"Tap(${LineNumbers(onSignal)},${LineNumbers(onMessage)},$behavior)"
  }

}
