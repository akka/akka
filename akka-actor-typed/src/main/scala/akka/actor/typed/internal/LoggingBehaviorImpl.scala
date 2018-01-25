/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.internal.adapter.LoggerAdapterImpl
import akka.annotation.InternalApi

import scala.reflect.ClassTag

/**
 * INTERNAL API
 *
 * Regular logging is supported directly on the ActorContext, but more advanced logging needs are covered here
 */
@InternalApi object LoggingBehaviorImpl {

  /**
   * MDC logging support
   *
   * @param mdcForMessage Is invoked before each message is handled, allowing to setup MDC, MDC is cleared after
   *                 each message processing by the inner behavior is done.
   * @param behavior The actual behavior handling the messages, the MDC is used for the log entries logged through
   *                 `ActorContext.log`
   */
  def withMdc[T: ClassTag](mdcForMessage: T ⇒ Map[String, Any], behavior: Behavior[T]): Behavior[T] =
    BehaviorImpl.intercept[T, T](
      beforeMessage = { (ctx, msg) ⇒
        ctx.log.asInstanceOf[LoggerAdapterImpl].mdc = mdcForMessage(msg)
        msg
      },
      beforeSignal = (_, _) ⇒ true,
      afterMessage = { (ctx, _, behavior) ⇒
        ctx.log.asInstanceOf[LoggerAdapterImpl].mdc = Map.empty
        behavior
      },
      afterSignal = (_, _, behavior) ⇒ behavior,
      behavior = behavior,
      toStringPrefix = "withMdc"
    )

}
