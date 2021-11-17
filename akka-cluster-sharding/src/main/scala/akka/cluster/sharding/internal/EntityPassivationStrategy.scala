/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.annotation.InternalApi
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.EntityId
import akka.util.RecencyList

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

@InternalApi
private[akka] object EntityPassivationStrategy {
  type PassivateEntities = immutable.Seq[EntityId]

  object PassivateEntities {
    val none: PassivateEntities = immutable.Seq.empty[EntityId]
  }

  def apply(settings: ClusterShardingSettings): EntityPassivationStrategy = {
    settings.passivationStrategy match {
      case ClusterShardingSettings.IdlePassivationStrategy(timeout) =>
        new IdleEntityPassivationStrategy(timeout)
      case ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(limit) =>
        new LeastRecentlyUsedEntityPassivationStrategy(limit)
      case ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(limit) =>
        new MostRecentlyUsedEntityPassivationStrategy(limit)
      case _ => DisabledEntityPassivationStrategy
    }
  }
}

/**
 * An entity passivation strategy, which is instantiated per active shard.
 */
@InternalApi
private[akka] sealed abstract class EntityPassivationStrategy {
  import EntityPassivationStrategy.PassivateEntities

  /**
   * Active shards in this region have been updated, which can trigger passivation.
   * @param activeShards updated number of active shards
   * @return entities to passivate in the associated shard
   */
  def shardsUpdated(activeShards: Int): PassivateEntities

  /**
   * A new entity instance has been created, which can trigger passivation.
   * @param id entity id for the new entity instance
   * @return entities to passivate, when active capacity has been reached
   */
  def entityCreated(id: EntityId): PassivateEntities

  /**
   * An entity instance has been touched. Recorded before message delivery.
   * @param id entity id for the touched entity instance
   */
  def entityTouched(id: EntityId): Unit

  /**
   * An entity instance has been terminated and should be removed from active tracking.
   * @param id entity id for the terminated entity instance
   */
  def entityTerminated(id: EntityId): Unit

  /**
   * An optional interval for time-based passivation strategies.
   * @return the scheduled interval to call the `intervalPassed` method
   */
  def scheduledInterval: Option[FiniteDuration]

  /**
   * Called each time the `scheduledInterval` has passed, if defined.
   * @return entities to passivate, if deemed inactive
   */
  def intervalPassed(): PassivateEntities
}

/**
 * No-op passivation strategy for when automatic passivation is disabled.
 */
@InternalApi
private[akka] object DisabledEntityPassivationStrategy extends EntityPassivationStrategy {
  import EntityPassivationStrategy.PassivateEntities

  override def shardsUpdated(activeShards: Int): PassivateEntities = PassivateEntities.none
  override def entityCreated(id: EntityId): PassivateEntities = PassivateEntities.none
  override def entityTouched(id: EntityId): Unit = ()
  override def entityTerminated(id: EntityId): Unit = ()
  override def scheduledInterval: Option[FiniteDuration] = None
  override def intervalPassed(): PassivateEntities = PassivateEntities.none
}

/**
 * Passivates entities when they have not received a message for a specified length of time.
 * @param timeout passivate idle entities after this timeout
 */
@InternalApi
private[akka] final class IdleEntityPassivationStrategy(timeout: FiniteDuration) extends EntityPassivationStrategy {
  import EntityPassivationStrategy.PassivateEntities

  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = Some(timeout / 2)

  override def shardsUpdated(activeShards: Int): PassivateEntities = PassivateEntities.none

  override def entityCreated(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    PassivateEntities.none
  }

  override def entityTouched(id: EntityId): Unit = recencyList.update(id)

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = recencyList.removeLeastRecentOutside(timeout)
}

/**
 * Passivate the least recently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * @param perRegionLimit active entity capacity for a shard region
 */
@InternalApi
private[akka] final class LeastRecentlyUsedEntityPassivationStrategy(perRegionLimit: Int)
    extends EntityPassivationStrategy {
  import EntityPassivationStrategy.PassivateEntities

  private var perShardLimit: Int = perRegionLimit
  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = None

  override def shardsUpdated(activeShards: Int): PassivateEntities = {
    perShardLimit = perRegionLimit / activeShards
    passivateExcessEntities()
  }

  override def entityCreated(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    passivateExcessEntities()
  }

  override def entityTouched(id: EntityId): Unit = recencyList.update(id)

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = PassivateEntities.none

  private def passivateExcessEntities(): PassivateEntities = {
    val excess = recencyList.size - perShardLimit
    if (excess > 0) recencyList.removeLeastRecent(excess) else PassivateEntities.none
  }
}

/**
 * Passivate the most recently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * @param perRegionLimit active entity capacity for a shard region
 */
@InternalApi
private[akka] final class MostRecentlyUsedEntityPassivationStrategy(perRegionLimit: Int)
    extends EntityPassivationStrategy {
  import EntityPassivationStrategy.PassivateEntities

  private var perShardLimit: Int = perRegionLimit
  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = None

  override def shardsUpdated(activeShards: Int): PassivateEntities = {
    perShardLimit = perRegionLimit / activeShards
    passivateExcessEntities()
  }

  override def entityCreated(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    passivateExcessEntities(skip = 1) // remove most recent before adding this created entity
  }

  override def entityTouched(id: EntityId): Unit = recencyList.update(id)

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = PassivateEntities.none

  private def passivateExcessEntities(skip: Int = 0): PassivateEntities = {
    val excess = recencyList.size - perShardLimit
    if (excess > 0) recencyList.removeMostRecent(excess, skip) else PassivateEntities.none
  }
}
