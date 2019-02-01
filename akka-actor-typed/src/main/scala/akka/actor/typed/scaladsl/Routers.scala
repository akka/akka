/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl
import akka.actor.typed.Behavior
import akka.actor.typed.internal.RouterPoolImpl

object Routers {
  
  /**
   * Spawn `poolSize` children with the given `behavior` and forward messages to them using round robin.
   * If a child is stopped it is removed from the pool, to have children restart on failure use supervision.
   * When all children are stopped the pool stops itself. To stop the pool from the outside, use `ActorContext.stop`
   * from the parent actor.
   *
   * Note that if a child stops there is a slight chance that messages still get delivered to it, and get lost,
   * before the pool sees that the child stopped. Therefore it is best to _not_ stop children arbitrarily.
   */
  def pool[T](poolSize: Int)(behavior: Behavior[T]): Behavior[T] =
    Behaviors.setup(ctx ⇒ new RouterPoolImpl[T](ctx, poolSize, behavior))

}
