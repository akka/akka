/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.Done
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId

import scala.concurrent.Future

/**
 * INTERNAL API
 *
 * Could potentially become an open SPI in the future.
 *
 * Implementations are responsible for each of the methods failing the returned future after a timeout.
 */
@InternalApi
private[akka] trait RememberEntitiesShardStore {

  /** Store the fact that the entity was started, complete future when write is confirmed */
  def addEntity(shardId: ShardId, entityId: EntityId): Future[Done]

  /** Store the fact that the entity was stopped, complete future when write is confirmed */
  def removeEntity(shardId: ShardId, entityId: EntityId): Future[Done]

  /** List all entities that should be alive for a given shard id */
  def getEntities(shardId: ShardId): Future[Set[EntityId]]
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object NoOpStore extends RememberEntitiesShardStore {
  private val AlreadyDone = Future.successful(Done)
  private val NoEntities = Future.successful(Set.empty[EntityId])
  override def addEntity(shardId: ShardId, entityId: EntityId): Future[Done] = AlreadyDone
  override def removeEntity(shardId: ShardId, entityId: EntityId): Future[Done] = AlreadyDone
  override def getEntities(shardId: ShardId): Future[Set[EntityId]] = NoEntities
}
