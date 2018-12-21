/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.actor.typed.internal.adapter.AbstractLogger
import akka.actor.typed.{ TypedActorContext, Behavior, BehaviorInterceptor, Signal }
import akka.annotation.InternalApi

import scala.collection.immutable.HashMap

/**
 * INTERNAL API
 */
@InternalApi private[akka] object WithMdcBehaviorInterceptor {
  val noMdcPerMessage = (_: Any) ⇒ Map.empty[String, Any]

  def apply[T](
    staticMdc:     Map[String, Any],
    mdcForMessage: T ⇒ Map[String, Any],
    behavior:      Behavior[T]): Behavior[T] = {

    val interceptor = new WithMdcBehaviorInterceptor[T](staticMdc, mdcForMessage)
    BehaviorImpl.intercept(interceptor)(behavior)
  }

}

/**
 * Support for Mapped Dagnostic Context for logging
 *
 * INTERNAL API
 */
@InternalApi private[akka] final class WithMdcBehaviorInterceptor[T] private (
  staticMdc:     Map[String, Any],
  mdcForMessage: T ⇒ Map[String, Any]) extends BehaviorInterceptor[T, T] {

  import BehaviorInterceptor._

  override def aroundStart(ctx: TypedActorContext[T], target: PreStartTarget[T]): Behavior[T] = {
    // when declaring we expect the outermost to win
    // for example with
    // val behavior = ...
    // val withMdc1 = withMdc(Map("first" -> true))
    // ...
    // val withMdc2 = withMdc(Map("second" -> true))
    // we'd expect the second one to be used
    // so we need to look through the stack and eliminate any MCD already existing
    def loop(next: Behavior[T]): Behavior[T] = {
      next match {
        case i: InterceptorImpl[T, T] if i.interceptor.isSame(this.asInstanceOf[BehaviorInterceptor[Any, Any]]) ⇒
          // eliminate that interceptor
          loop(i.nestedBehavior)

        case w: WrappingBehavior[T, T] ⇒
          val nested = w.nestedBehavior
          val inner = loop(nested)
          if (inner eq nested) w
          else w.replaceNested(inner)

        case b ⇒ b
      }
    }

    loop(target.start(ctx))
  }

  // in the normal case, a new withMDC replaces the previous one
  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = other match {
    case _: WithMdcBehaviorInterceptor[_] ⇒ true
    case _                                ⇒ false
  }

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    val mdc = merge(staticMdc, mdcForMessage(msg))
    ctx.asScala.log.asInstanceOf[AbstractLogger].mdc = mdc
    val next =
      try {
        target(ctx, msg)
      } finally {
        ctx.asScala.log.asInstanceOf[AbstractLogger].mdc = Map.empty
      }
    next
  }

  override def aroundSignal(ctx: TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    ctx.asScala.log.asInstanceOf[AbstractLogger].mdc = staticMdc
    try {
      target(ctx, signal)
    } finally {
      ctx.asScala.log.asInstanceOf[AbstractLogger].mdc = Map.empty
    }
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

  override def toString: String = s"WithMdc($staticMdc)"
}
