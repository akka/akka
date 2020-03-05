/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.javadsl

import java.util.Optional

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.annotation.DoNotInherit
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.japi.function

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
 * The sharded entities are running on top of Akka Cluster Sharding and are kept alive through periodic pinging.
 * When the cluster topology changes, depending on the shard allocation strategy used, the entities are redistributed
 * across the nodes of the cluster (and started by the next periodic ping).
 *
 * Not for user extension.
 */
@DoNotInherit
abstract class ShardedDaemonProcess {

  /**
   * Start a specific number of actors and should always be alive.
   * The name is combined with an integer to get a unique id among the actors in the set.
   */
  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: function.Function[Integer, Behavior[T]]): Unit

  /**
   * Start a specific number of actors and should always be alive.
   * The name is combined with an integer to get a unique id among the actors in the set.
   */
  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: function.Function[Integer, Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T]): Unit

}
