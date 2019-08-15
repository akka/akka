/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.actor.typed.{ Behavior, BehaviorInterceptor, Signal, TypedActorContext }
import akka.annotation.InternalApi
import org.slf4j.MDC
import scala.collection.JavaConverters._

import scala.collection.immutable.HashMap
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi private[akka] object WithMdcBehaviorInterceptor {
  val noMdcPerMessage = (_: Any) => Map.empty[String, String]

  def apply[T: ClassTag](
      staticMdc: Map[String, String],
      mdcForMessage: T => Map[String, String],
      behavior: Behavior[T]): Behavior[T] = {
    BehaviorImpl.intercept(() => new WithMdcBehaviorInterceptor[T](staticMdc, mdcForMessage))(behavior)
  }

}

/**
 * Support for Mapped Dagnostic Context for logging
 *
 * INTERNAL API
 */
@InternalApi private[akka] final class WithMdcBehaviorInterceptor[T: ClassTag] private (
    staticMdc: Map[String, String],
    mdcForMessage: T => Map[String, String])
    extends BehaviorInterceptor[T, T] {

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
        case i: InterceptorImpl[T, T] if i.interceptor.isSame(this.asInstanceOf[BehaviorInterceptor[Any, Any]]) =>
          // eliminate that interceptor
          loop(i.nestedBehavior)

        case i: InterceptorImpl[T, T] =>
          val nested = i.nestedBehavior
          val inner = loop(nested)
          if (inner eq nested) i
          else i.replaceNested(inner)

        case b => b
      }
    }

    loop(target.start(ctx))
  }

  // in the normal case, a new withMDC replaces the previous one
  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = other match {
    case _: WithMdcBehaviorInterceptor[_] => true
    case _                                => false
  }

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    val mdc = merge(staticMdc, mdcForMessage(msg))
    MDC.getMDCAdapter.setContextMap(mdc.asJava)
    val next =
      try {
        target(ctx, msg)
      } finally {
        MDC.clear()
      }
    next
  }

  override def aroundSignal(ctx: TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] = {
    MDC.getMDCAdapter.setContextMap(staticMdc.asJava)
    try {
      target(ctx, signal)
    } finally {
      MDC.clear()
    }
  }

  private def merge(staticMdc: Map[String, String], mdcForMessage: Map[String, String]): Map[String, String] = {
    if (staticMdc.isEmpty) mdcForMessage
    else if (mdcForMessage.isEmpty) staticMdc
    else if (staticMdc.isInstanceOf[HashMap[String, String]] && mdcForMessage.isInstanceOf[HashMap[String, String]]) {
      // merged is more efficient than ++
      mdcForMessage.asInstanceOf[HashMap[String, String]].merged(staticMdc.asInstanceOf[HashMap[String, String]])(null)
    } else {
      staticMdc ++ mdcForMessage
    }
  }

  override def toString: String = s"WithMdc($staticMdc)"
}
