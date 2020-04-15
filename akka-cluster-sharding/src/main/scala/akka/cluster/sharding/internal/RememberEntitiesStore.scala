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
 * Created once for the shard guardian
 */
@InternalApi
private[akka] trait RememberEntitiesProvider {

  /**
   * Called once per started shard coordinator to create the remember entities coordinator store.
   *
   * Note that this is not used for the deprecated persistent coordinator which has its own impl for keeping track of
   * remembered shards.
   *
   * @return an actor that handles the protocol defined in [[RememberEntitiesCoordinatorStore]]
   */
  def coordinatorStoreProps(): Props

  /**
   * Called once per started shard to create the remember entities shard store
   * @return an actor that handles the protocol defined in [[RememberEntitiesShardStore]]
   */
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
  // SPI protocol for a remember entities shard store
  sealed trait Command

  sealed trait UpdateEntityCommand extends Command {
    def entityId: EntityId
  }
  final case class AddEntity(entityId: EntityId) extends UpdateEntityCommand
  final case class RemoveEntity(entityId: EntityId) extends UpdateEntityCommand
  // responses for UpdateEntity add and remove
  final case class UpdateDone(entityId: EntityId)

  case object GetEntities extends Command
  final case class RememberedEntities(entities: Set[EntityId])

}

/**
 * INTERNAL API
 *
 * Could potentially become an open SPI in the future.
 */
@InternalApi
private[akka] object RememberEntitiesCoordinatorStore {
  // SPI protocol for a remember entities coordinator store
  sealed trait Command

  /**
   * Sent once for every started shard, should result in a response of either
   * UpdateDone or UpdateFailed
   */
  final case class AddShard(entityId: ShardId) extends Command
  final case class UpdateDone(entityId: ShardId)
  final case class UpdateFailed(entityId: ShardId)

  case object GetShards extends Command
  final case class RememberedShards(entities: Set[ShardId])
}
