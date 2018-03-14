/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.actor.typed.Behavior.DeferredBehavior
import akka.actor.typed.internal.adapter.AbstractLogger
import akka.actor.typed.{ ActorContext, Behavior, ExtensibleBehavior, Signal }
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object WithMdcBehavior {
  val noMdcPerMessage = (_: Any) ⇒ Map.empty[String, Any]

  def apply[T](
    staticMdc:     Map[String, Any],
    mdcForMessage: T ⇒ Map[String, Any],
    behavior:      Behavior[T]): Behavior[T] =
    // FIXME this is needed for every custom decorating behavior - provide public tool for it
    behavior match {
      case d: DeferredBehavior[T] ⇒
        DeferredBehavior[T] { ctx ⇒
          val c = ctx.asInstanceOf[akka.actor.typed.ActorContext[T]]
          new WithMdcBehavior(staticMdc, mdcForMessage, Behavior.validateAsInitial(Behavior.start(d, c)))
        }
      case b ⇒
        new WithMdcBehavior[T](staticMdc, mdcForMessage, b)
    }

}

/**
 * Support for Mapped Dagnostic Context for logging
 *
 * INTERNAL API
 */
@InternalApi private[akka] final class WithMdcBehavior[T] private (
  staticMdc:     Map[String, Any],
  mdcForMessage: T ⇒ Map[String, Any],
  behavior:      Behavior[T]) extends ExtensibleBehavior[T] {

  def wrapWithMdc(nextBehavior: Behavior[T], ctx: ActorContext[T]) =
    Behavior.wrap(behavior, nextBehavior, ctx)(new WithMdcBehavior(staticMdc, mdcForMessage, _))

  override def receiveMessage(ctx: ActorContext[T], msg: T): Behavior[T] = {
    val mdc = staticMdc ++ mdcForMessage(msg)
    ctx.asScala.log.asInstanceOf[AbstractLogger].mdc = mdc
    val next =
      try {
        Behavior.interpretMessage(behavior, ctx, msg)
      } finally {
        ctx.asScala.log.asInstanceOf[AbstractLogger].mdc = Map.empty
      }
    wrapWithMdc(next, ctx)
  }

  override def receiveSignal(ctx: ActorContext[T], signal: Signal): Behavior[T] = {
    val next = Behavior.interpretSignal(behavior, ctx, signal)
    wrapWithMdc(next, ctx)
  }

  override def toString: String = s"WithMdc($behavior)"
}
