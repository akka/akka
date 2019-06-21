/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import akka.actor.typed.Behavior
import akka.actor.typed.BehaviorInterceptor
import akka.actor.typed.Signal
import akka.actor.typed.TypedActorContext
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object GuardianStartupBehavior {
  case object Start
}

/**
 * INTERNAL API
 *
 * Messages to the user provided guardian must be deferred while the actor system is starting up. This
 * behavior delays starting the user provided behavior until the Start command is delivered from the actor
 * system, and we know that the bootstrap is completed and the actor context can be accessed.
 */
@InternalApi
private[akka] final class GuardianStartupBehavior[T](val guardianBehavior: Behavior[T]) extends AbstractBehavior[Any] {

  import GuardianStartupBehavior.Start

  private var tempStash: List[T] = Nil

  override def onMessage(msg: Any): Behavior[Any] =
    msg match {
      case Start =>
        // ctx is not available initially so we cannot use it until here
        Behaviors.withStash[T](1000, stash => {
           tempStash.reverse.foreach(stash.stash)
           tempStash = null
           stash.unstashAll(Behaviors.intercept(() => new GuardianStopInterceptor[T])(guardianBehavior))
        }).unsafeCast[Any]
      case other =>
        tempStash = other.asInstanceOf[T] :: tempStash
        this
    }

}

/**
 * INTERNAL API
 *
 * When the user guardian is stopped the ActorSystem is terminated, but to run CoordinatedShutdown
 * as part of that we must intercept when the guardian is stopped and call ActorSystem.terminate()
 * explicitly.
 */
@InternalApi private[akka] final class GuardianStopInterceptor[T] extends BehaviorInterceptor[T, T] {
  override def aroundReceive(
      ctx: TypedActorContext[T],
      msg: T,
      target: BehaviorInterceptor.ReceiveTarget[T]): Behavior[T] = {
    val next = target(ctx, msg)
    interceptStopped(ctx, next)
  }

  override def aroundSignal(
      ctx: TypedActorContext[T],
      signal: Signal,
      target: BehaviorInterceptor.SignalTarget[T]): Behavior[T] = {
    val next = target(ctx, signal)
    interceptStopped(ctx, next)
  }

  private def interceptStopped(ctx: TypedActorContext[T], next: Behavior[T]): Behavior[T] = {
    if (Behavior.isAlive(next))
      next
    else {
      ctx.asScala.system.terminate()
      Behaviors.ignore
    }
  }
}
