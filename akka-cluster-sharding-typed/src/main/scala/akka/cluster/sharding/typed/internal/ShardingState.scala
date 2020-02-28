/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import scala.concurrent.Future

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion.CurrentShardRegionState
import akka.cluster.sharding.typed.ClusterShardingQuery
import akka.cluster.sharding.typed.GetClusterShardingStats
import akka.cluster.sharding.typed.GetShardRegionState
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion
import akka.pattern.AskTimeoutException
import akka.util.Timeout

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ShardingState {

  def behavior(classicSharding: ClusterSharding): Behavior[ClusterShardingQuery] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case GetShardRegionState(key, replyTo) =>
          if (classicSharding.getShardTypeNames.contains(key.name)) {
            classicSharding.shardRegion(key.name).tell(ShardRegion.GetShardRegionState, replyTo.toClassic)
          } else {
            replyTo ! CurrentShardRegionState(Set.empty)
          }
          Behaviors.same

        case GetClusterShardingStats(timeout, replyTo) =>
          import akka.pattern.ask
          import akka.pattern.pipe
          implicit val t: Timeout = timeout
          implicit val ec = context.system.executionContext

          val regions = classicSharding.shardTypeNames.map(classicSharding.shardRegion)

          if (regions.nonEmpty) {
            Future
              .firstCompletedOf(regions.map { region =>
                (region ? ShardRegion.GetClusterShardingStats(timeout)).mapTo[ShardRegion.ClusterShardingStats]
              })
              .recover {
                case _: AskTimeoutException =>
                  ShardRegion.ClusterShardingStats(Map.empty)
              }
              .pipeTo(replyTo.toClassic)
          } else {
            replyTo ! ShardRegion.ClusterShardingStats(Map.empty)
          }

          Behaviors.same
      }
    }

}
