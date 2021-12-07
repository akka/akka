/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.annotation.InternalApi
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.EntityId
import akka.util.{ FrequencyList, OptionVal, RecencyList }

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
      case ClusterShardingSettings.IdlePassivationStrategy(timeout, interval) =>
        new IdleEntityPassivationStrategy(new IdleCheck(timeout, interval))
      case ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(limit, idle) =>
        val idleCheck = idle.map(idle => new IdleCheck(idle.timeout, idle.interval))
        new LeastRecentlyUsedEntityPassivationStrategy(limit, idleCheck)
      case ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(limit, idle) =>
        val idleCheck = idle.map(idle => new IdleCheck(idle.timeout, idle.interval))
        new MostRecentlyUsedEntityPassivationStrategy(limit, idleCheck)
      case ClusterShardingSettings.LeastFrequentlyUsedPassivationStrategy(limit, idle) =>
        val idleCheck = idle.map(idle => new IdleCheck(idle.timeout, idle.interval))
        new LeastFrequentlyUsedEntityPassivationStrategy(limit, idleCheck)
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
   * An entity instance has been touched. Recorded before message delivery.
   * @param id entity id for the touched entity instance
   * @return entities to passivate, when active capacity has been reached
   */
  def entityTouched(id: EntityId): PassivateEntities

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
  override def entityTouched(id: EntityId): PassivateEntities = PassivateEntities.none
  override def entityTerminated(id: EntityId): Unit = ()
  override def scheduledInterval: Option[FiniteDuration] = None
  override def intervalPassed(): PassivateEntities = PassivateEntities.none
}

@InternalApi
private[akka] final class IdleCheck(val timeout: FiniteDuration, val interval: FiniteDuration)

/**
 * Passivates entities when they have not received a message for a specified length of time.
 * @param idleCheck passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class IdleEntityPassivationStrategy(idleCheck: IdleCheck) extends EntityPassivationStrategy {

  import EntityPassivationStrategy.PassivateEntities

  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = Some(idleCheck.interval)

  override def shardsUpdated(activeShards: Int): PassivateEntities = PassivateEntities.none

  override def entityTouched(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    PassivateEntities.none
  }

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = recencyList.removeLeastRecentOutside(idleCheck.timeout)
}

/**
 * Passivate the least recently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * @param perRegionLimit active entity capacity for a shard region
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class LeastRecentlyUsedEntityPassivationStrategy(perRegionLimit: Int, idleCheck: Option[IdleCheck])
    extends EntityPassivationStrategy {

  import EntityPassivationStrategy.PassivateEntities

  private var perShardLimit: Int = perRegionLimit
  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def shardsUpdated(activeShards: Int): PassivateEntities = {
    perShardLimit = perRegionLimit / activeShards
    passivateExcessEntities()
  }

  override def entityTouched(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    passivateExcessEntities()
  }

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    recencyList.removeLeastRecentOutside(idle.timeout)
  }

  private def passivateExcessEntities(): PassivateEntities = {
    val excess = recencyList.size - perShardLimit
    if (excess > 0) recencyList.removeLeastRecent(excess) else PassivateEntities.none
  }
}

/**
 * Passivate the most recently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * @param perRegionLimit active entity capacity for a shard region
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class MostRecentlyUsedEntityPassivationStrategy(perRegionLimit: Int, idleCheck: Option[IdleCheck])
    extends EntityPassivationStrategy {

  import EntityPassivationStrategy.PassivateEntities

  private var perShardLimit: Int = perRegionLimit
  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def shardsUpdated(activeShards: Int): PassivateEntities = {
    perShardLimit = perRegionLimit / activeShards
    passivateExcessEntities()
  }

  override def entityTouched(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    passivateExcessEntities(skip = 1) // remove most recent before adding this created entity
  }

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    recencyList.removeLeastRecentOutside(idle.timeout)
  }

  private def passivateExcessEntities(skip: Int = 0): PassivateEntities = {
    val excess = recencyList.size - perShardLimit
    if (excess > 0) recencyList.removeMostRecent(excess, skip) else PassivateEntities.none
  }
}

/**
 * Passivate the least frequently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * @param perRegionLimit active entity capacity for a shard region
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class LeastFrequentlyUsedEntityPassivationStrategy(
    perRegionLimit: Int,
    idleCheck: Option[IdleCheck])
    extends EntityPassivationStrategy {

  import EntityPassivationStrategy.PassivateEntities

  private var perShardLimit: Int = perRegionLimit
  private val frequencyList =
    if (idleCheck.isDefined) FrequencyList.withOverallRecency.empty[EntityId]
    else FrequencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def shardsUpdated(activeShards: Int): PassivateEntities = {
    perShardLimit = perRegionLimit / activeShards
    passivateExcessEntities()
  }

  override def entityTouched(id: EntityId): PassivateEntities = {
    frequencyList.update(id)
    passivateExcessEntities(skip = OptionVal.Some(id)) // make sure the newly created entity is still active
  }

  override def entityTerminated(id: EntityId): Unit = frequencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    frequencyList.removeOverallLeastRecentOutside(idle.timeout)
  }

  private def passivateExcessEntities(skip: OptionVal[EntityId] = OptionVal.none[EntityId]): PassivateEntities = {
    val excess = frequencyList.size - perShardLimit
    if (excess > 0) frequencyList.removeLeastFrequent(excess, skip) else PassivateEntities.none
  }
}
