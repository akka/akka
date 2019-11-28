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
@InternalApi private[akka] object ShardingState {

  def behavior(classicSharding: ClusterSharding): Behavior[ClusterShardingQuery] = Behaviors.receiveMessage {
    case GetShardRegionState(key, replyTo) =>
      if (classicSharding.getShardTypeNames.contains(key.name)) {
        classicSharding.shardRegion(key.name).tell(ShardRegion.GetShardRegionState, replyTo.toClassic)
      } else {
        replyTo ! CurrentShardRegionState(Set.empty)
      }
      Behaviors.same
  }

}
