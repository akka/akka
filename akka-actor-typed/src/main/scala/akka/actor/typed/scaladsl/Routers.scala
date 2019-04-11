/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl
import akka.actor.typed.Behavior
import akka.actor.typed.internal.routing.GroupRouterBuilder
import akka.actor.typed.internal.routing.PoolRouterBuilder
import akka.actor.typed.receptionist.ServiceKey
import akka.annotation.DoNotInherit

object Routers {

  /**
   * A router that will keep track of the available routees registered to the [[akka.actor.typed.receptionist.Receptionist]]
   * and route over those by random selection.
   *
   * In a clustered app this means the routees could live on any node in the cluster.
   * The current impl does not try to avoid sending messages to unreachable cluster nodes.
   *
   * Note that there is a delay between a routee stopping and this being detected by the receptionist and another
   * before the group detects this. Because of this it is best to unregister routees from the receptionist and not stop
   * until the deregistration is complete to minimize the risk of lost messages.
   */
  def group[T](key: ServiceKey[T]): GroupRouter[T] =
    new GroupRouterBuilder[T](key)

  /**
   * Spawn `poolSize` children with the given `behavior` and forward messages to them using round robin.
   * If a child is stopped it is removed from the pool. To have children restart on failure, use supervision.
   * When all children are stopped the pool stops itself. To stop the pool from the outside, use `ActorContext.stop`
   * from the parent actor.
   *
   * Note that if a child stops, there is a slight chance that messages still get delivered to it, and get lost,
   * before the pool sees that the child stopped. Therefore it is best to _not_ stop children arbitrarily.
   */
  def pool[T](poolSize: Int)(behavior: Behavior[T]): PoolRouter[T] =
    new PoolRouterBuilder[T](poolSize, behavior)

}

/**
 * Provides builder style configuration options for group routers
 *
 * Not for user extension. Use [[Routers#group]] to create
 */
@DoNotInherit
trait GroupRouter[T] extends Behavior[T] {

  /**
   * Route messages by randomly selecting the routee from the available routees. This is the default for group routers.
   */
  def withRandomRouting(): GroupRouter[T]

  /**
   * Route messages by using round robin.
   *
   * Round robin gives fair routing where every available routee gets the same amount of messages as long as the set
   * of routees stays relatively stable, but may be unfair if the set of routees changes a lot.
   */
  def withRoundRobinRouting(): GroupRouter[T]

}

/**
 * Provides builder style configuration options for pool routers
 *
 * Not for user extension. Use [[Routers#pool]] to create
 */
@DoNotInherit
trait PoolRouter[T] extends Behavior[T] {

  /**
   * Route messages by randomly selecting the routee from the available routees.
   *
   * Random routing makes it less likely that every `poolsize` message from a single producer ends up in the same
   * mailbox of a slow actor.
   */
  def withRandomRouting(): PoolRouter[T]

  /**
   * Route messages through round robin, providing a fair distribution of messages across the routees.
   *
   * Round robin gives fair routing where every available routee gets the same amount of messages
   *
   * This is the default for pool routers.
   */
  def withRoundRobinRouting(): PoolRouter[T]

  /**
   * Set a new pool size from the one set at construction
   */
  def withPoolSize(poolSize: Int): PoolRouter[T]
}
