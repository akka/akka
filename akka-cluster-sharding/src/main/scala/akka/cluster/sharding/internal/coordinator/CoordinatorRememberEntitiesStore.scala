/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.internal.coordinator

import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion.ShardId

/**
 * INTERNAL API
 *
 * Protocol for remember entities (shards) from the coordinator
 */
@InternalApi
private[akka] object CoordinatorRememberEntitiesStore {
  sealed trait Command
  case object GetShards extends Command
  final case class InitialShards(shardIds: Set[ShardId])

  final case class AddShard(shardId: ShardId) extends Command
  final case class ShardIdAdded(shardId: ShardId)
}
