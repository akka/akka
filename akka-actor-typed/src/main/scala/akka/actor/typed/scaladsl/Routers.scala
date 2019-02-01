/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl
import akka.actor.typed.Behavior
import akka.actor.typed.internal.{ RouterGroupImpl, RouterPoolImpl }
import akka.actor.typed.receptionist.ServiceKey

object Routers {

  /**
   * A router that will keep track of the available routees registered to the [[akka.actor.typed.receptionist.Receptionist]]
   * and route over those by random selection.
   *
   * In a clustered app this means the routees could live on any node in the cluster.
   * The current impl does not try to avoid sending messages to unreachable cluster nodes.
   *
   * Note that there is a delay between a routee stopping and this being detected by the receptionist, and another
   * before the group detects this, therefore it is best to unregister routees from the receptionist and not stop
   * until the deregistration is complete to minimize the risk of lost messages.
   */
  def group[T](key: ServiceKey[T]): Behavior[T] =
    // fixme: potential detection of cluster and selecting a different impl
    Behaviors.setup(ctx ⇒ new RouterGroupImpl[T](ctx, key))

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
