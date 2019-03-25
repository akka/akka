/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.routing

import akka.actor.typed._
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.InternalApi

/**
 * Provides builder style configuration options for group routers while still being a behavior that can be spawned
 *
 * INTERNAL API
 */
@InternalApi
private[akka] final case class GroupRouterBuilder[T] private[akka] (
    key: ServiceKey[T],
    logicFactory: () => RoutingLogic[T] = () => new RoutingLogics.RandomLogic[T]())
    extends javadsl.GroupRouter[T]
    with scaladsl.GroupRouter[T] {

  // deferred creation of the actual router
  def apply(ctx: TypedActorContext[T]): Behavior[T] = new GroupRouterImpl[T](ctx.asScala, key, logicFactory())

  def withRandomRouting(): GroupRouterBuilder[T] = copy(logicFactory = () => new RoutingLogics.RandomLogic[T]())

  def withRoundRobinRouting(): GroupRouterBuilder[T] = copy(logicFactory = () => new RoutingLogics.RoundRobinLogic[T])

}

/**
 * INTERNAL API
 */
@InternalApi
private final class GroupRouterImpl[T](ctx: ActorContext[T], serviceKey: ServiceKey[T], routingLogic: RoutingLogic[T])
    extends AbstractBehavior[T] {

  // casting trix to avoid having to wrap incoming messages - note that this will cause problems if intercepting
  // messages to a router
  ctx.system.receptionist ! Receptionist.Subscribe(serviceKey, ctx.self.unsafeUpcast[Any].narrow[Receptionist.Listing])
  private var routeesEmpty = true

  def onMessage(msg: T): Behavior[T] = msg match {
    case serviceKey.Listing(update) =>
      // we don't need to watch, because receptionist already does that
      routingLogic.routeesUpdated(update)
      routeesEmpty = update.isEmpty
      this
    case msg: T @unchecked =>
      if (!routeesEmpty) routingLogic.selectRoutee() ! msg
      else ctx.system.deadLetters ! Dropped(msg, ctx.self)
      this
  }
}
