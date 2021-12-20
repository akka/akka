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

/**
 * INTERNAL API
 */
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
 * INTERNAL API: An entity passivation strategy, which is instantiated per active shard.
 */
@InternalApi
private[akka] sealed abstract class EntityPassivationStrategy {
  import EntityPassivationStrategy.PassivateEntities

  /**
   * The per-region active entity limit has been updated, which can trigger passivation.
   * @param newLimit the new per-region active entity limit
   * @return entities to passivate in the associated shard
   */
  def limitUpdated(newLimit: Int): PassivateEntities

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
 * INTERNAL API: No-op passivation strategy for when automatic passivation is disabled.
 */
@InternalApi
private[akka] object DisabledEntityPassivationStrategy extends EntityPassivationStrategy {
  import EntityPassivationStrategy.PassivateEntities

  override def limitUpdated(newLimit: Int): PassivateEntities = PassivateEntities.none
  override def shardsUpdated(activeShards: Int): PassivateEntities = PassivateEntities.none
  override def entityTouched(id: EntityId): PassivateEntities = PassivateEntities.none
  override def entityTerminated(id: EntityId): Unit = ()
  override def scheduledInterval: Option[FiniteDuration] = None
  override def intervalPassed(): PassivateEntities = PassivateEntities.none
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class IdleCheck(val timeout: FiniteDuration, val interval: FiniteDuration)

/**
 * INTERNAL API: Passivates entities when they have not received a message for a specified length of time.
 * @param idleCheck passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class IdleEntityPassivationStrategy(idleCheck: IdleCheck) extends EntityPassivationStrategy {

  import EntityPassivationStrategy.PassivateEntities

  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = Some(idleCheck.interval)

  override def limitUpdated(newLimit: Int): PassivateEntities = PassivateEntities.none

  override def shardsUpdated(activeShards: Int): PassivateEntities = PassivateEntities.none

  override def entityTouched(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    PassivateEntities.none
  }

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = recencyList.removeLeastRecentOutside(idleCheck.timeout)
}

/**
 * INTERNAL API: Shared base class for limit-based passivation strategies.
 * @param initialLimit initial active entity capacity for a shard region
 */
@InternalApi
private[akka] abstract class LimitBasedEntityPassivationStrategy(initialLimit: Int) extends EntityPassivationStrategy {
  import EntityPassivationStrategy.PassivateEntities

  protected var activeShards: Int = 1
  protected var perRegionLimit: Int = initialLimit
  protected var perShardLimit: Int = perRegionLimit

  override def limitUpdated(newPerRegionLimit: Int): PassivateEntities = {
    perRegionLimit = newPerRegionLimit
    perShardLimit = perRegionLimit / activeShards
    passivateEntitiesOnLimitUpdate()
  }

  override def shardsUpdated(newActiveShards: Int): PassivateEntities = {
    activeShards = newActiveShards
    perShardLimit = perRegionLimit / activeShards
    passivateEntitiesOnLimitUpdate()
  }

  protected def passivateEntitiesOnLimitUpdate(): PassivateEntities
}

/**
 * INTERNAL API: Passivate the least recently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * @param initialLimit initial active entity capacity for a shard region
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class LeastRecentlyUsedEntityPassivationStrategy(initialLimit: Int, idleCheck: Option[IdleCheck])
    extends LimitBasedEntityPassivationStrategy(initialLimit) {

  import EntityPassivationStrategy.PassivateEntities

  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def entityTouched(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    passivateExcessEntities()
  }

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    recencyList.removeLeastRecentOutside(idle.timeout)
  }

  override protected def passivateEntitiesOnLimitUpdate(): PassivateEntities = passivateExcessEntities()

  private def passivateExcessEntities(): PassivateEntities = {
    val excess = recencyList.size - perShardLimit
    if (excess > 0) recencyList.removeLeastRecent(excess) else PassivateEntities.none
  }
}

/**
 * INTERNAL API
 *
 * Passivate the least recently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * Active entities are tracked in multiple recency lists, where entities are promoted to higher-level
 * segments on subsequent accesses, and demoted through levels when segments become full.
 * The proportions of the segmented levels can be configured as fractions of the overall limit.
 * @param initialLimit initial active entity capacity for a shard region
 * @param proportions proportions of the segmented levels
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class SegmentedLeastRecentlyUsedEntityPassivationStrategy(
    initialLimit: Int,
    proportions: immutable.Seq[Double],
    idleCheck: Option[IdleCheck])
    extends LimitBasedEntityPassivationStrategy(initialLimit) {

  import EntityPassivationStrategy.PassivateEntities

  private def limits: immutable.Seq[Int] = proportions.map(p => (p * perShardLimit).toInt)

  private val segmentedRecencyList =
    if (idleCheck.isDefined) SegmentedRecencyList.withOverallRecency.empty[EntityId](limits)
    else SegmentedRecencyList.empty[EntityId](limits)

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def entityTouched(id: EntityId): PassivateEntities = {
    segmentedRecencyList.update(id)
    passivateExcessEntities()
  }

  override def entityTerminated(id: EntityId): Unit = segmentedRecencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    segmentedRecencyList.removeOverallLeastRecentOutside(idle.timeout)
  }

  override protected def passivateEntitiesOnLimitUpdate(): PassivateEntities = {
    segmentedRecencyList.updateLimits(limits)
    passivateExcessEntities()
  }

  private def passivateExcessEntities(): PassivateEntities = segmentedRecencyList.removeLeastRecentOverLimit()
}

/**
 * INTERNAL API
 *
 * Passivate the most recently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * @param initialLimit initial active entity capacity for a shard region
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class MostRecentlyUsedEntityPassivationStrategy(initialLimit: Int, idleCheck: Option[IdleCheck])
    extends LimitBasedEntityPassivationStrategy(initialLimit) {

  import EntityPassivationStrategy.PassivateEntities

  private val recencyList = RecencyList.empty[EntityId]

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def entityTouched(id: EntityId): PassivateEntities = {
    recencyList.update(id)
    passivateExcessEntities(skip = 1) // remove most recent before adding this created entity
  }

  override def entityTerminated(id: EntityId): Unit = recencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    recencyList.removeLeastRecentOutside(idle.timeout)
  }

  override protected def passivateEntitiesOnLimitUpdate(): PassivateEntities = passivateExcessEntities()

  private def passivateExcessEntities(skip: Int = 0): PassivateEntities = {
    val excess = recencyList.size - perShardLimit
    if (excess > 0) recencyList.removeMostRecent(excess, skip) else PassivateEntities.none
  }
}

/**
 * INTERNAL API
 *
 * Passivate the least frequently used entities when the number of active entities in a shard region
 * reaches a limit. The per-region limit is divided evenly among the active shards in a region.
 * @param initialLimit initial active entity capacity for a shard region
 * @param dynamicAging whether to apply "dynamic aging" as entities are passivated
 * @param idleCheck optionally passivate idle entities after the given timeout, checking every interval
 */
@InternalApi
private[akka] final class LeastFrequentlyUsedEntityPassivationStrategy(
    initialLimit: Int,
    dynamicAging: Boolean,
    idleCheck: Option[IdleCheck])
    extends LimitBasedEntityPassivationStrategy(initialLimit) {

  import EntityPassivationStrategy.PassivateEntities

  private val frequencyList =
    if (idleCheck.isDefined) FrequencyList.withOverallRecency.empty[EntityId](dynamicAging)
    else FrequencyList.empty[EntityId](dynamicAging)

  override val scheduledInterval: Option[FiniteDuration] = idleCheck.map(_.interval)

  override def entityTouched(id: EntityId): PassivateEntities = {
    // first remove excess entities so that dynamic aging is updated
    // and the adjusted age is applied to any new entities on update
    // adjust the expected size by 1 if this is a newly activated entity
    val adjustment = if (frequencyList.contains(id)) 0 else 1
    val passivated = passivateExcessEntities(adjustment)
    frequencyList.update(id)
    passivated
  }

  override def entityTerminated(id: EntityId): Unit = frequencyList.remove(id)

  override def intervalPassed(): PassivateEntities = idleCheck.fold(PassivateEntities.none) { idle =>
    frequencyList.removeOverallLeastRecentOutside(idle.timeout)
  }

  override protected def passivateEntitiesOnLimitUpdate(): PassivateEntities = passivateExcessEntities()

  private def passivateExcessEntities(adjustment: Int = 0): PassivateEntities = {
    val excess = frequencyList.size - perShardLimit + adjustment
    if (excess > 0) frequencyList.removeLeastFrequent(excess) else PassivateEntities.none
  }

}
