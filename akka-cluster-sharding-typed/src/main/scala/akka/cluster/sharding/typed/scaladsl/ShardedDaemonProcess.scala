/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import scala.reflect.ClassTag

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.internal.ShardedDaemonProcessImpl
import akka.cluster.sharding.typed.javadsl

object ShardedDaemonProcess extends ExtensionId[ShardedDaemonProcess] {
  override def createExtension(system: ActorSystem[_]): ShardedDaemonProcess = new ShardedDaemonProcessImpl(system)
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
trait ShardedDaemonProcess extends Extension { javadslSelf: javadsl.ShardedDaemonProcess =>

  /**
   * Start a specific number of actors that is then kept alive in the cluster.
   * @param behaviorFactory Given a unique id of `0` until `numberOfInstance` create the behavior for that actor.
   */
  def init[T](name: String, numberOfInstances: Int, behaviorFactory: Int => Behavior[T])(
      implicit classTag: ClassTag[T]): Unit

  /**
   * Start a specific number of actors that is then kept alive in the cluster.
   *
   * @param behaviorFactory Given a unique id of `0` until `numberOfInstance` create the behavior for that actor.
   * @param stopMessage sent to the actors when they need to stop because of a rebalance across the nodes of the cluster
   *                    or cluster shutdown.
   */
  def init[T](name: String, numberOfInstances: Int, behaviorFactory: Int => Behavior[T], stopMessage: T)(
      implicit classTag: ClassTag[T]): Unit

  /**
   * Start a specific number of actors, each with a unique numeric id in the set, that is then kept alive in the cluster.
   * @param behaviorFactory Given a unique id of `0` until `numberOfInstance` create the behavior for that actor.
   * @param stopMessage if defined sent to the actors when they need to stop because of a rebalance across the nodes of the cluster
   *                    or cluster shutdown.
   */
  def init[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: Int => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T])(implicit classTag: ClassTag[T]): Unit

  /**
   * Start a specific number of actors, each with a unique numeric id in the set, that is then kept alive in the cluster.
   * @param behaviorFactory Given a unique id of `0` until `numberOfInstance` create the behavior for that actor.
   * @param stopMessage if defined sent to the actors when they need to stop because of a rebalance across the nodes of the cluster
   *                    or cluster shutdown.
   * @param shardAllocationStrategy if defined used by entities to control the shard allocation
   */
  def init[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: Int => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T],
      shardAllocationStrategy: Option[ShardAllocationStrategy])(implicit classTag: ClassTag[T]): Unit

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def asJava: javadsl.ShardedDaemonProcess = javadslSelf

}
