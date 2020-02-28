/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.typed.internal.ClusterActorSetImpl

import scala.concurrent.duration.FiniteDuration
import akka.util.JavaDurationConverters._
import com.typesafe.config.Config

object ClusterActorSetSettings {

  def apply(system: ActorSystem[_]): ClusterActorSetSettings = {
    apply(system.settings.config.getConfig("akka.cluster.actor-set"))
  }

  def apply(config: Config): ClusterActorSetSettings = {
    val keepAliveInterval = config.getDuration("keep-alive-interval").asScala

    new ClusterActorSetSettings(keepAliveInterval)
  }

}

class ClusterActorSetSettings private[akka] (val keepAliveInterval: FiniteDuration) {

  /**
   * The interval each parent of the sharded set is pinged from each node in the cluster.
   *
   * Note: How the sharded set is kept alive may change in the future meaning this setting may go away.
   */
  @ApiMayChange
  def withKeepAliveInterval(keepAliveInterval: FiniteDuration): ClusterActorSetSettings =
    new ClusterActorSetSettings(keepAliveInterval)
}

object ClusterActorSet extends ExtensionId[ClusterActorSet] {
  override def createExtension(system: ActorSystem[_]): ClusterActorSet = new ClusterActorSetImpl(system)
}

/**
 * This extension provides sharding functionality of a pre set number of actors in a cluster.
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
trait ClusterActorSet extends Extension {

  /**
   * Start a specific number of actors and should always be alive. Each get a unique id among the actors in the set.
   * Use default settings from config.
   */
  def init[T](numberOfEntities: Int, behaviorFactory: EntityId => Behavior[T], stopMessage: T): Unit

  /**
   * Start a specific number of actors and should always be alive. Each get a unique id among the actors in the set.
   */
  def init[T](
      settings: ClusterActorSetSettings,
      numberOfEntities: Int,
      behaviorFactory: EntityId => Behavior[T],
      stopMessage: T): Unit

  /**
   * Start a set of predefined actors that should always be alive. Use default settings from config.
   */
  def init[T](identities: Set[EntityId], behaviorFactory: EntityId => Behavior[T], stopMessage: T): Unit

  /**
   * Start a set of predefined actors that should always be alive
   */
  def init[T](
      settings: ClusterActorSetSettings,
      identities: Set[EntityId],
      behaviorFactory: EntityId => Behavior[T],
      stopMessage: T): Unit
}
