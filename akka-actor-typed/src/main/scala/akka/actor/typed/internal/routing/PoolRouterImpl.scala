/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing

import akka.actor.typed._
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object PoolRouterImpl {
  def apply[T](poolSize: Int, behavior: Behavior[T], logicFactory: () ⇒ RoutingLogic[T]): Behavior[T] =
    Behaviors.setup(ctx ⇒ new PoolRouterImpl[T](ctx, poolSize, behavior, logicFactory()))
}

/**
 * INTERNAL API
 */
@InternalApi
private final class PoolRouterImpl[T](ctx: ActorContext[T], poolSize: Int, behavior: Behavior[T], logic: RoutingLogic[T]) extends AbstractBehavior[T] {
  if (poolSize < 1) throw new IllegalArgumentException(s"pool size must be positive, was $poolSize")

  private var routees = (1 to poolSize).map { _ ⇒
    val child = ctx.spawnAnonymous(behavior)
    ctx.watch(child)
    child
  }.toArray

  def onMessage(msg: T): Behavior[T] = {
    val recipient = logic.selectRoutee(routees)
    recipient.tell(msg)
    Behavior.same
  }

  override def onSignal: PartialFunction[Signal, Behavior[T]] = {
    case Terminated(child) ⇒
      ctx.log.warning(s"Pool child stopped [${child.path}]")
      val childIdx = routees.indexOf(child)
      if (childIdx == -1)
        throw new IllegalStateException(s"Got termination message for [${child.path}] which isn't a routee of this router")
      val originalRoutees = routees
      routees = routees.filterNot(_ == child)
      if (routees.nonEmpty) {
        logic.routeesUpdated(originalRoutees, routees)
        Behavior.same
      } else Behavior.stopped
  }

}

