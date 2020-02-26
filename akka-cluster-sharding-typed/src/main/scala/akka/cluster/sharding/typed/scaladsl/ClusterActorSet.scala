/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
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
    val numberOfShards = config.getInt("number-of-shards")
    val keepAliveInterval = config.getDuration("keep-alive-interval").asScala

    new ClusterActorSetSettings(keepAliveInterval, numberOfShards)
  }

}

class ClusterActorSetSettings private[akka] (val keepAliveInterval: FiniteDuration, val numberOfShards: Int) {
  def withKeepAliveInterval(keepAliveInterval: FiniteDuration): ClusterActorSetSettings =
    new ClusterActorSetSettings(keepAliveInterval, numberOfShards)
  def withNumberOfShards(numberOfShards: Int): ClusterActorSetSettings =
    new ClusterActorSetSettings(keepAliveInterval, numberOfShards)
}

object ClusterActorSet extends ExtensionId[ClusterActorSet] {
  override def createExtension(system: ActorSystem[_]): ClusterActorSet = new ClusterActorSetImpl(system)
}

/**
 * This extension provides sharding functionality of a pre set number of actors in a cluster.
 *
 * The typical use case is when you have a task that can be divided in a number of workers, each doing a
 * sharded part of the work, for example consuming a subset the read side events from Akka Persistence through
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
   */
  def init(settings: ClusterActorSetSettings, numberOfEntities: Int, behaviorFactory: EntityId => Behavior[_]): Unit

  /**
   * Start a set of predefined actors that should always be alive
   */
  def init(settings: ClusterActorSetSettings, identities: Set[EntityId], behaviorFactory: EntityId => Behavior[_]): Unit
}
