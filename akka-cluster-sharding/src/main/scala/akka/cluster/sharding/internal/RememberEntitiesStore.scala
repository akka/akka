/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.Props
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId

/**
 * INTERNAL API
 *
 * Created once from the shard region, called once per started shard to create the remember entities shard store
 */
@InternalApi
private[akka] trait RememberEntitiesShardStoreProvider {
  def shardStoreProps(shardId: ShardId): Props
}

/**
 * INTERNAL API
 *
 * Could potentially become an open SPI in the future.
 *
 * Implementations are responsible for each of the methods failing the returned future after a timeout.
 */
@InternalApi
private[akka] object RememberEntitiesShardStore {
  // SPI protocol for a remember entities store
  sealed trait Command

  case class AddEntity(entityId: EntityId) extends Command
  case class RemoveEntity(entity: EntityId) extends Command
  // response for both add and remove
  case class UpdateDone(entityId: EntityId)

  case object GetEntities extends Command
  case class RememberedEntities(entities: Set[EntityId])

  case object StopStore
}
