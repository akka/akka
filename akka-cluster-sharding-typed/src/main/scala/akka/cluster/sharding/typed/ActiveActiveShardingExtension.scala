/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.annotation.ApiMayChange
import akka.annotation.DoNotInherit
import akka.cluster.sharding.typed.internal.ActiveActiveShardingExtensionImpl
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.typed.ReplicaId

/**
 * Extension for running active active in sharding by starting one separate instance of sharding per replica.
 * The sharding instances can be confined to datacenters or cluster roles or run on the same set of cluster nodes.
 */
@ApiMayChange
object ActiveActiveShardingExtension extends ExtensionId[ActiveActiveShardingExtension] {

  override def createExtension(system: ActorSystem[_]): ActiveActiveShardingExtension =
    new ActiveActiveShardingExtensionImpl(system)

}

/**
 * Not for user extension.
 */
@DoNotInherit
@ApiMayChange
trait ActiveActiveShardingExtension extends Extension {

  /**
   * Init one instance sharding per replica in the given settings and return a [[ActiveActiveSharding]] representing those.
   *
   * @tparam M The type of messages the active active event sourced actor accepts
   * @tparam E The type of envelope used for routing messages to actors, the same for all replicas
   *
   * Note, multiple calls on the same node will not start new sharding instances but will return a new instance of [[ActiveActiveSharding]]
   */
  def init[M, E](settings: ActiveActiveShardingSettings[M, E]): ActiveActiveSharding[M, E]
}

/**
 * Represents the sharding instances for the replicas of one active active entity type
 *
 * Not for user extension.
 */
@DoNotInherit
@ApiMayChange
trait ActiveActiveSharding[M, E] {

  /**
   * Scala API: Returns the entity ref for each replica for user defined routing/replica selection
   */
  def entityRefsFor(entityId: String): Map[ReplicaId, EntityRef[M]]

  /**
   * Chose a replica randomly for each message being sent to the EntityRef.
   */
  def randomRefFor(entityId: String): EntityRef[M]

  // FIXME ideally we'd want some different clever strategies here but that is cut out of scope for now
}
