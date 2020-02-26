/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.typed.scaladsl

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.ExtensionId
import akka.annotation.DoNotInherit
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.typed.internal.ClusterEntitySetImpl

import scala.concurrent.duration.FiniteDuration
import akka.util.JavaDurationConverters._

object ClusterEntitySetSettings {
  def apply[M, E](identities: Set[EntityId], entity: Entity[M, E])(
      implicit system: ActorSystem[_]): ClusterEntitySetSettings[M, E] = {
    val keepAliveInterval = system.settings.config.getDuration("akka.cluster.sharding.entity-set.keep-alive-interval").asScala

    new ClusterEntitySetSettings(identities, entity, keepAliveInterval, )
  }

}

class ClusterEntitySetSettings[M, E] private[akka] (
    val identities: Set[EntityId],
    val entity: Entity[M, E],
    val keepAliveInterval: FiniteDuration,
    val numberOfShards: Int) {
  def withKeepAliveInterval(keepAliveInterval: FiniteDuration): ClusterEntitySetSettings[M, E] =
    new ClusterEntitySetSettings(identities, entity, keepAliveInterval, numberOfShards)
  def withIdentities(identities: Set[EntityId]): ClusterEntitySetSettings[M, E] =
    new ClusterEntitySetSettings(identities, entity, keepAliveInterval, numberOfShards)
  def withNumberOfShards(numberOfShards: Int): ClusterEntitySetSettings[M, E] =
    new ClusterEntitySetSettings(identities, entity, keepAliveInterval, numberOfShards)
}

object ClusterEntitySet extends ExtensionId[ClusterEntitySet] {
  override def createExtension(system: ActorSystem[_]): ClusterEntitySet = new ClusterEntitySetImpl
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
trait ClusterEntitySet {
  // FIXME do we want a way to interact with the entities through entity id or a broadcast?
  def init[M, E](clusterEntitySetSettings: ClusterEntitySetSettings): ActorRef[E]
}
