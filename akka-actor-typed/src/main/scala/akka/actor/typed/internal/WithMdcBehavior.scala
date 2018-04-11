/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import scala.collection.immutable.HashMap

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

    behavior match {
      case d: DeferredBehavior[T] ⇒
        DeferredBehavior[T] { ctx ⇒
          val started = Behavior.validateAsInitial(Behavior.start(d, ctx))
          chooseOutermostOrWrap(staticMdc, mdcForMessage, started)
        }
      case b ⇒
        chooseOutermostOrWrap(staticMdc, mdcForMessage, b)
    }

  // when declaring we expect the outermost to win
  // for example with
  // val behavior = ...
  // val withMdc1 = withMdc(Map("first" -> true))
  // ...
  // val withMdc2 = withMdc(Map("second" -> true))
  // we'd expect the second one to be used
  private def chooseOutermostOrWrap[T](staticMdc: Map[String, Any], mdcForMessage: T ⇒ Map[String, Any], behavior: Behavior[T]) =
    behavior match {
      case inner: WithMdcBehavior[T] ⇒ new WithMdcBehavior(staticMdc, mdcForMessage, inner.behavior)
      case other                     ⇒ new WithMdcBehavior(staticMdc, mdcForMessage, other)
    }

}

/**
 * Support for Mapped Dagnostic Context for logging
 *
 * INTERNAL API
 */
@InternalApi private[akka] final class WithMdcBehavior[T] private (
  staticMdc:            Map[String, Any],
  mdcForMessage:        T ⇒ Map[String, Any],
  private val behavior: Behavior[T]) extends ExtensibleBehavior[T] {

  // re-wrap with mdc so that it doesn't get lost if behavior changes,
  // unless it changes to new MDC, then throw this away and use the new one
  def wrapWithMdc(nextBehavior: Behavior[T], ctx: ActorContext[T]) =
    Behavior.wrap(behavior, nextBehavior, ctx) {
      case inner: WithMdcBehavior[T] ⇒ inner
      case other                     ⇒ new WithMdcBehavior(staticMdc, mdcForMessage, other)
    }

  override def receive(ctx: ActorContext[T], msg: T): Behavior[T] = {
    val mdc = merge(staticMdc, mdcForMessage(msg))
    ctx.asScala.log.asInstanceOf[AbstractLogger].mdc = mdc
    val next =
      try {
        Behavior.interpretMessage(behavior, ctx, msg)
      } finally {
        ctx.asScala.log.asInstanceOf[AbstractLogger].mdc = Map.empty
      }
    wrapWithMdc(next, ctx)
  }

  private def merge(staticMdc: Map[String, Any], mdcForMessage: Map[String, Any]): Map[String, Any] = {
    if (staticMdc.isEmpty) mdcForMessage
    else if (mdcForMessage.isEmpty) staticMdc
    else if (staticMdc.isInstanceOf[HashMap[String, Any]] && mdcForMessage.isInstanceOf[HashMap[String, Any]]) {
      // merged is more efficient than ++
      mdcForMessage.asInstanceOf[HashMap[String, Any]].merged(staticMdc.asInstanceOf[HashMap[String, Any]])(null)
    } else {
      staticMdc ++ mdcForMessage
    }
  }

  override def receiveSignal(ctx: ActorContext[T], signal: Signal): Behavior[T] = {
    val next = Behavior.interpretSignal(behavior, ctx, signal)
    wrapWithMdc(next, ctx)
  }

  override def toString: String = s"WithMdc(${staticMdc}, $behavior)"
}
