/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.ActorRef
import akka.cluster.sharding.ShardRegion.CurrentShardRegionState
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

/**
 * Protocol for querying sharding state e.g. A ShardRegion's state
 */
sealed trait ClusterShardingQuery

/**
 * Query the ShardRegion state for the given entity type key. This will get the state of the
 * local ShardRegion's state.
 */
final case class GetShardRegionState(entityTypeKey: EntityTypeKey[_], replyTo: ActorRef[CurrentShardRegionState])
    extends ClusterShardingQuery {

  /**
   * Java API
   *
   * Query the ShardRegion state for the given entity type key. This will get the state of the
   * local ShardRegion's state.
   */
  def this(entityTypeKey: javadsl.EntityTypeKey[_], replyTo: ActorRef[CurrentShardRegionState]) =
    this(entityTypeKey.asScala, replyTo)
}

// TODO - GetClusterShardingStats
