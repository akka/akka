/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.annotation.InternalApi
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.EntityId
import akka.util.{ FrequencyList, RecencyList, SegmentedRecencyList }

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
      case ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(limit, segmented, idle) =>
        val idleCheck = idle.map(idle => new IdleCheck(idle.timeout, idle.interval))
        if (segmented.isEmpty) new LeastRecentlyUsedEntityPassivationStrategy(limit, idleCheck)
        else new SegmentedLeastRecentlyUsedEntityPassivationStrategy(limit, segmented, idleCheck)
      case ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(limit, idle) =>
        val idleCheck = idle.map(idle => new IdleCheck(idle.timeout, idle.interval))
        new MostRecentlyUsedEntityPassivationStrategy(limit, idleCheck)
      case ClusterShardingSettings.LeastFrequentlyUsedPassivationStrategy(limit, dynamicAging, idle) =>
        val idleCheck = idle.map(idle => new IdleCheck(idle.timeout, idle.interval))
        new LeastFrequentlyUsedEntityPassivationStrategy(limit, dynamicAging, idleCheck)
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

  override def entityCreated(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    PassivateEntities.none
  }

  override def entityTouched(id: EntityId): Unit = recencyList.update(id)

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

  override def entityCreated(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    passivateExcessEntities()
  }

  override def entityTouched(id: EntityId): Unit = recencyList.update(id)

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
 * Passivate the least recently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * Active entities are tracked in multiple recency lists, where entities are promoted to higher-level
 * segments on subsequent accesses, and demoted through levels when segments become full.
 * The proportions of the segmented levels can be configured as fractions of the overall limit.
 * @param perRegionLimit active entity capacity for a shard region
 * @param proportions proportions of the segmented levels
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class SegmentedLeastRecentlyUsedEntityPassivationStrategy(
    perRegionLimit: Int,
    proportions: immutable.Seq[Double],
    idleCheck: Option[IdleCheck])
    extends EntityPassivationStrategy {

  import EntityPassivationStrategy.PassivateEntities

  private var perShardLimit: Int = perRegionLimit

  private def limits: immutable.Seq[Int] = proportions.map(p => (p * perShardLimit).toInt)

  private val segmentedRecencyList =
    if (idleCheck.isDefined) SegmentedRecencyList.withOverallRecency.empty[EntityId](limits)
    else SegmentedRecencyList.empty[EntityId](limits)

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def shardsUpdated(activeShards: Int): PassivateEntities = {
    perShardLimit = perRegionLimit / activeShards
    segmentedRecencyList.updateLimits(limits)
    passivateExcessEntities()
  }

  override def entityCreated(id: EntityId): PassivateEntities = {
    segmentedRecencyList.update(id)
    passivateExcessEntities()
  }

  override def entityTouched(id: EntityId): Unit = segmentedRecencyList.update(id)

  override def entityTerminated(id: EntityId): Unit = segmentedRecencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    segmentedRecencyList.removeOverallLeastRecentOutside(idle.timeout)
  }

  private def passivateExcessEntities(): PassivateEntities = segmentedRecencyList.removeLeastRecentOverLimit()
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

  override def entityCreated(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    passivateExcessEntities(skip = 1) // remove most recent before adding this created entity
  }

  override def entityTouched(id: EntityId): Unit = recencyList.update(id)

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
 * @param dynamicAging whether to apply "dynamic aging" as entities are passivated
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class LeastFrequentlyUsedEntityPassivationStrategy(
    perRegionLimit: Int,
    dynamicAging: Boolean,
    idleCheck: Option[IdleCheck])
    extends EntityPassivationStrategy {

  import EntityPassivationStrategy.PassivateEntities

  private var perShardLimit: Int = perRegionLimit
  private val frequencyList =
    if (idleCheck.isDefined) FrequencyList.withOverallRecency.empty[EntityId](dynamicAging)
    else FrequencyList.empty[EntityId](dynamicAging)

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def shardsUpdated(activeShards: Int): PassivateEntities = {
    perShardLimit = perRegionLimit / activeShards
    passivateExcessEntities()
  }

  override def entityCreated(id: EntityId): PassivateEntities = {
    val passivated = passivateExcessEntities(adjustment = +1)
    frequencyList.update(id)
    passivated
  }

  override def entityTouched(id: EntityId): Unit = frequencyList.update(id)

  override def entityTerminated(id: EntityId): Unit = frequencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    frequencyList.removeOverallLeastRecentOutside(idle.timeout)
  }

  private def passivateExcessEntities(adjustment: Int = 0): PassivateEntities = {
    val excess = frequencyList.size - perShardLimit + adjustment
    if (excess > 0) frequencyList.removeLeastFrequent(excess) else PassivateEntities.none
  }
}
