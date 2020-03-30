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
 * Created once from the shard region, called once per started shard to create the remember entities shard store
 */
@InternalApi
private[akka] trait RememberEntitiesShardStoreProvider {

  def createStoreForShard(shardId: ShardId): RememberEntitiesShardStore
}

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
  def addEntity(entityId: EntityId): Future[Done]

  /** Store the fact that the entity was stopped, complete future when write is confirmed */
  def removeEntity(entityId: EntityId): Future[Done]

  /** List all entities that should be alive for a given shard id */
  def getEntities(): Future[Set[EntityId]]

  def stop(): Unit
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object NoOpStore extends RememberEntitiesShardStore with RememberEntitiesShardStoreProvider {
  private val AlreadyDone = Future.successful(Done)
  private val NoEntities = Future.successful(Set.empty[EntityId])
  override def addEntity(entityId: EntityId): Future[Done] = AlreadyDone
  override def removeEntity(entityId: EntityId): Future[Done] = AlreadyDone
  override def getEntities(): Future[Set[EntityId]] = NoEntities
  override def stop(): Unit = ()

  override def createStoreForShard(shardId: ShardId): RememberEntitiesShardStore = this
}
