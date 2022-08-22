/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl

import java.util.function.IntFunction
import java.util.Optional

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.annotation.DoNotInherit
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings

object ShardedDaemonProcess {
  def get(system: ActorSystem[_]): ShardedDaemonProcess =
    akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess(system).asJava
}

/**
 * This extension runs a pre set number of actors in a cluster.
 *
 * The typical use case is when you have a task that can be divided in a number of workers, each doing a
 * sharded part of the work, for example consuming the read side events from Akka Persistence through
 * tagged events where each tag decides which consumer that should consume the event.
 *
 * Each named set needs to be started on all the nodes of the cluster on start up.
 *
 * The processes are spread out across the cluster, when the cluster topology changes the processes may be stopped
 * and started anew on a new node to rebalance them.
 *
 * Not for user extension.
 */
@DoNotInherit
abstract class ShardedDaemonProcess {

  /**
   * Start a specific number of actors that is then kept alive in the cluster.
   * @param behaviorFactory Given a unique id of `0` until `numberOfInstance` create the behavior for that actor.
   */
  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]]): Unit

  /**
   * Start a specific number of actors that is then kept alive in the cluster.
   *
   * @param behaviorFactory Given a unique id of `0` until `numberOfInstance` create the behavior for that actor.
   * @param stopMessage sent to the actors when they need to stop because of a rebalance across the nodes of the cluster
   *                    or cluster shutdown.
   */
  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      stopMessage: T): Unit

  /**
   * Start a specific number of actors, each with a unique numeric id in the set, that is then kept alive in the cluster.
   * @param behaviorFactory Given a unique id of `0` until `numberOfInstance` create the behavior for that actor.
   * @param stopMessage if defined sent to the actors when they need to stop because of a rebalance across the nodes of the cluster
   *                    or cluster shutdown.
   */
  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T]): Unit

  /**
   * Start a specific number of actors, each with a unique numeric id in the set, that is then kept alive in the cluster.
   * @param behaviorFactory Given a unique id of `0` until `numberOfInstance` create the behavior for that actor.
   * @param stopMessage if defined sent to the actors when they need to stop because of a rebalance across the nodes of the cluster
   *                    or cluster shutdown.
   * @param shardAllocationStrategy if defined used by entities to control the shard allocation
   */
  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: IntFunction[Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T],
      shardAllocationStrategy: Optional[ShardAllocationStrategy]): Unit

}
