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
private[akka] final case class PoolRouterBuilder[T](
    poolSize: Int,
    behaviorFactory: () => Behavior[T],
    logicFactory: () => RoutingLogic[T] = () => new RoutingLogics.RoundRobinLogic[T])
    extends javadsl.PoolRouter[T]
    with scaladsl.PoolRouter[T] {
  if (poolSize < 1) throw new IllegalArgumentException(s"pool size must be positive, was $poolSize")

  // deferred creation of the actual router
  def apply(ctx: TypedActorContext[T]): Behavior[T] =
    new PoolRouterImpl[T](ctx.asScala, poolSize, behaviorFactory, logicFactory())

  def withRandomRouting(): PoolRouterBuilder[T] = copy(logicFactory = () => new RoutingLogics.RandomLogic[T]())

  def withRoundRobinRouting(): PoolRouterBuilder[T] = copy(logicFactory = () => new RoutingLogics.RoundRobinLogic[T])

  def withPoolSize(poolSize: Int): PoolRouterBuilder[T] = copy(poolSize = poolSize)
}

/**
 * INTERNAL API
 */
@InternalApi
private final class PoolRouterImpl[T](
    ctx: ActorContext[T],
    poolSize: Int,
    behaviorFactory: () => Behavior[T],
    logic: RoutingLogic[T])
    extends AbstractBehavior[T] {

  (1 to poolSize).foreach { _ =>
    val child = ctx.spawnAnonymous(behaviorFactory())
    ctx.watch(child)
    child
  }
  onRouteesChanged()

  private def onRouteesChanged(): Unit = {
    val children = ctx.children.toSet.asInstanceOf[Set[ActorRef[T]]]
    logic.routeesUpdated(children)
  }

  def onMessage(msg: T): Behavior[T] = {
    logic.selectRoutee() ! msg
    this
  }

  override def onSignal: PartialFunction[Signal, Behavior[T]] = {
    case Terminated(child) =>
      // Note that if several children are stopping concurrently children may already be empty
      // for the `Terminated` we receive for the first child. This means it is not certain that
      // there will be a log entry per child in those cases (it does not make sense to keep the
      // pool alive just to get the logging right when there are no routees available)
      if (ctx.children.nonEmpty) {
        ctx.log.debug("Pool child stopped [{}]", child.path)
        onRouteesChanged()
        this
      } else {
        ctx.log.info("Last pool child stopped, stopping pool [{}]", ctx.self.path)
        Behaviors.stopped
      }
  }

}
