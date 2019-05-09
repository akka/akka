/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.{ ClusterSharding, ShardRegion }
import akka.cluster.sharding.ShardRegion.CurrentShardRegionState
import akka.actor.typed.scaladsl.adapter._
import akka.annotation.InternalApi
import akka.cluster.sharding.typed.{ ClusterShardingQuery, GetShardRegionState }

/**
 * INTERNAL API
 */
@InternalApi
object ShardingState {

  def behavior(untypedSharding: ClusterSharding): Behavior[ClusterShardingQuery] = Behaviors.receiveMessage {
    case GetShardRegionState(key, replyTo) =>
      if (untypedSharding.getShardTypeNames.contains(key.name)) {
        untypedSharding.shardRegion(key.name).tell(ShardRegion.GetShardRegionState, replyTo.toUntyped)
      } else {
        replyTo ! CurrentShardRegionState(Set.empty)
      }
      Behavior.same
  }

}
