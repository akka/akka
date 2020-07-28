/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.cluster.sharding.typed.internal.ReplicatedShardingExtensionImpl
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.typed.ReplicaId
import java.util.{ Map => JMap }

import akka.actor.typed.ActorRef

/**
 * Extension for running active active in sharding by starting one separate instance of sharding per replica.
 * The sharding instances can be confined to datacenters or cluster roles or run on the same set of cluster nodes.
 */
@ApiMayChange
object ReplicatedShardingExtension extends ExtensionId[ReplicatedShardingExtension] {

  override def createExtension(system: ActorSystem[_]): ReplicatedShardingExtension =
    new ReplicatedShardingExtensionImpl(system)

  def get(system: ActorSystem[_]): ReplicatedShardingExtension = apply(system)

}

/**
 * Not for user extension.
 */
@DoNotInherit
@ApiMayChange
trait ReplicatedShardingExtension extends Extension {

  /**
   * Init one instance sharding per replica in the given settings and return a [[ReplicatedSharding]] representing those.
   *
   * @tparam M The type of messages the active active event sourced actor accepts
   * @tparam E The type of envelope used for routing messages to actors, the same for all replicas
   *
   * Note, multiple calls on the same node will not start new sharding instances but will return a new instance of [[ReplicatedSharding]]
   */
  def init[M, E](settings: ReplicatedShardingSettings[M, E]): ReplicatedSharding[M, E]
}

/**
 * Represents the sharding instances for the replicas of one active active entity type
 *
 * Not for user extension.
 */
@DoNotInherit
@ApiMayChange
trait ReplicatedSharding[M, E] {

  /**
   * Scala API: Returns the actor refs for the shard region or proxies of sharding for each replica for user defined
   * routing/replica selection.
   */
  def shardingRefs: Map[ReplicaId, ActorRef[E]]

  /**
   * Java API: Returns the actor refs for the shard region or proxies of sharding for each replica for user defined
   * routing/replica selection.
   */
  def getShardingRefs: JMap[ReplicaId, ActorRef[E]]

  /**
   * Scala API: Returns the entity ref for each replica for user defined routing/replica selection
   *
   * This can only be used if the default [[ShardingEnvelope]] is used, when using custom envelopes or in message
   * entity ids you will need to use [[#shardingRefs]]
   */
  def entityRefsFor(entityId: String): Map[ReplicaId, EntityRef[M]]

  /**
   * Java API: Returns the entity ref for each replica for user defined routing/replica selection
   *
   * This can only be used if the default [[ShardingEnvelope]] is used, when using custom envelopes or in message
   * entity ids you will need to use [[#getShardingRefs]]
   */
  def getEntityRefsFor(entityId: String): JMap[ReplicaId, EntityRef[M]]

}
