/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.typed.ActorRef
import akka.cluster.sharding.ShardRegion.{ ClusterShardingStats, CurrentShardRegionState }
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.util.JavaDurationConverters

import scala.concurrent.duration.FiniteDuration

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

/**
 * Query the statistics about the currently running sharded entities in the
 * entire cluster. If the given `timeout` is reached without answers from all
 * shard regions the reply will contain an empty map of regions.
 *
 * @param timeout the timeout applied to querying all alive regions
 * @param replyTo the actor to send the result to
 */
final case class GetClusterShardingStats(timeout: FiniteDuration, replyTo: ActorRef[ClusterShardingStats])
    extends ClusterShardingQuery {

  /**
   * Java API
   *
   * Query the statistics about the currently running sharded entities in the
   * entire cluster. If the given `timeout` is reached without answers from all
   * shard regions the reply will contain an empty map of regions.
   */
  def this(timeout: java.time.Duration, replyTo: ActorRef[ClusterShardingStats]) =
    this(JavaDurationConverters.asFiniteDuration(timeout), replyTo)
}
