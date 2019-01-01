/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorSystem
import akka.actor.NoSerializationVerificationNeeded
import akka.annotation.InternalApi
import com.typesafe.config.Config
import akka.cluster.Cluster
import akka.cluster.singleton.ClusterSingletonManagerSettings
import akka.util.JavaDurationConverters._

object ClusterShardingSettings {

  val StateStoreModePersistence = "persistence"
  val StateStoreModeDData = "ddata"

  /**
   * Create settings from the default configuration
   * `akka.cluster.sharding`.
   */
  def apply(system: ActorSystem): ClusterShardingSettings =
    apply(system.settings.config.getConfig("akka.cluster.sharding"))

  /**
   * Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.sharding`.
   */
  def apply(config: Config): ClusterShardingSettings = {
    val tuningParameters = new TuningParameters(
      coordinatorFailureBackoff = config.getDuration("coordinator-failure-backoff", MILLISECONDS).millis,
      retryInterval = config.getDuration("retry-interval", MILLISECONDS).millis,
      bufferSize = config.getInt("buffer-size"),
      handOffTimeout = config.getDuration("handoff-timeout", MILLISECONDS).millis,
      shardStartTimeout = config.getDuration("shard-start-timeout", MILLISECONDS).millis,
      shardFailureBackoff = config.getDuration("shard-failure-backoff", MILLISECONDS).millis,
      entityRestartBackoff = config.getDuration("entity-restart-backoff", MILLISECONDS).millis,
      rebalanceInterval = config.getDuration("rebalance-interval", MILLISECONDS).millis,
      snapshotAfter = config.getInt("snapshot-after"),
      keepNrOfBatches = config.getInt("keep-nr-of-batches"),
      leastShardAllocationRebalanceThreshold =
        config.getInt("least-shard-allocation-strategy.rebalance-threshold"),
      leastShardAllocationMaxSimultaneousRebalance =
        config.getInt("least-shard-allocation-strategy.max-simultaneous-rebalance"),
      waitingForStateTimeout = config.getDuration("waiting-for-state-timeout", MILLISECONDS).millis,
      updatingStateTimeout = config.getDuration("updating-state-timeout", MILLISECONDS).millis,
      entityRecoveryStrategy = config.getString("entity-recovery-strategy"),
      entityRecoveryConstantRateStrategyFrequency = config.getDuration("entity-recovery-constant-rate-strategy.frequency", MILLISECONDS).millis,
      entityRecoveryConstantRateStrategyNumberOfEntities = config.getInt("entity-recovery-constant-rate-strategy.number-of-entities"))

    val coordinatorSingletonSettings = ClusterSingletonManagerSettings(config.getConfig("coordinator-singleton"))

    val passivateIdleAfter =
      if (config.getString("passivate-idle-entity-after").toLowerCase == "off") Duration.Zero
      else config.getDuration("passivate-idle-entity-after", MILLISECONDS).millis

    new ClusterShardingSettings(
      role = roleOption(config.getString("role")),
      rememberEntities = config.getBoolean("remember-entities"),
      journalPluginId = config.getString("journal-plugin-id"),
      snapshotPluginId = config.getString("snapshot-plugin-id"),
      stateStoreMode = config.getString("state-store-mode"),
      passivateIdleEntityAfter = passivateIdleAfter,
      tuningParameters,
      coordinatorSingletonSettings)
  }

  /**
   * Java API: Create settings from the default configuration
   * `akka.cluster.sharding`.
   */
  def create(system: ActorSystem): ClusterShardingSettings = apply(system)

  /**
   * Java API: Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.sharding`.
   */
  def create(config: Config): ClusterShardingSettings = apply(config)

  /**
   * INTERNAL API
   */
  private[akka] def roleOption(role: String): Option[String] =
    if (role == "") None else Option(role)

  class TuningParameters(
    val coordinatorFailureBackoff:                          FiniteDuration,
    val retryInterval:                                      FiniteDuration,
    val bufferSize:                                         Int,
    val handOffTimeout:                                     FiniteDuration,
    val shardStartTimeout:                                  FiniteDuration,
    val shardFailureBackoff:                                FiniteDuration,
    val entityRestartBackoff:                               FiniteDuration,
    val rebalanceInterval:                                  FiniteDuration,
    val snapshotAfter:                                      Int,
    val keepNrOfBatches:                                    Int,
    val leastShardAllocationRebalanceThreshold:             Int,
    val leastShardAllocationMaxSimultaneousRebalance:       Int,
    val waitingForStateTimeout:                             FiniteDuration,
    val updatingStateTimeout:                               FiniteDuration,
    val entityRecoveryStrategy:                             String,
    val entityRecoveryConstantRateStrategyFrequency:        FiniteDuration,
    val entityRecoveryConstantRateStrategyNumberOfEntities: Int) {

    require(
      entityRecoveryStrategy == "all" || entityRecoveryStrategy == "constant",
      s"Unknown 'entity-recovery-strategy' [$entityRecoveryStrategy], valid values are 'all' or 'constant'")

    // included for binary compatibility
    def this(
      coordinatorFailureBackoff:                          FiniteDuration,
      retryInterval:                                      FiniteDuration,
      bufferSize:                                         Int,
      handOffTimeout:                                     FiniteDuration,
      shardStartTimeout:                                  FiniteDuration,
      shardFailureBackoff:                                FiniteDuration,
      entityRestartBackoff:                               FiniteDuration,
      rebalanceInterval:                                  FiniteDuration,
      snapshotAfter:                                      Int,
      leastShardAllocationRebalanceThreshold:             Int,
      leastShardAllocationMaxSimultaneousRebalance:       Int,
      waitingForStateTimeout:                             FiniteDuration,
      updatingStateTimeout:                               FiniteDuration,
      entityRecoveryStrategy:                             String,
      entityRecoveryConstantRateStrategyFrequency:        FiniteDuration,
      entityRecoveryConstantRateStrategyNumberOfEntities: Int) = {
      this(
        coordinatorFailureBackoff,
        retryInterval,
        bufferSize,
        handOffTimeout,
        shardStartTimeout,
        shardFailureBackoff,
        entityRestartBackoff,
        rebalanceInterval,
        snapshotAfter,
        2,
        leastShardAllocationRebalanceThreshold,
        leastShardAllocationMaxSimultaneousRebalance,
        waitingForStateTimeout,
        updatingStateTimeout,
        entityRecoveryStrategy,
        entityRecoveryConstantRateStrategyFrequency,
        entityRecoveryConstantRateStrategyNumberOfEntities)
    }

    // included for binary compatibility
    def this(
      coordinatorFailureBackoff:                    FiniteDuration,
      retryInterval:                                FiniteDuration,
      bufferSize:                                   Int,
      handOffTimeout:                               FiniteDuration,
      shardStartTimeout:                            FiniteDuration,
      shardFailureBackoff:                          FiniteDuration,
      entityRestartBackoff:                         FiniteDuration,
      rebalanceInterval:                            FiniteDuration,
      snapshotAfter:                                Int,
      leastShardAllocationRebalanceThreshold:       Int,
      leastShardAllocationMaxSimultaneousRebalance: Int,
      waitingForStateTimeout:                       FiniteDuration,
      updatingStateTimeout:                         FiniteDuration) = {
      this(
        coordinatorFailureBackoff,
        retryInterval,
        bufferSize,
        handOffTimeout,
        shardStartTimeout,
        shardFailureBackoff,
        entityRestartBackoff,
        rebalanceInterval,
        snapshotAfter,
        leastShardAllocationRebalanceThreshold,
        leastShardAllocationMaxSimultaneousRebalance,
        waitingForStateTimeout,
        updatingStateTimeout,
        "all",
        100.milliseconds,
        5)
    }

  }
}

/**
 * @param role specifies that this entity type requires cluster nodes with a specific role.
 *   If the role is not specified all nodes in the cluster are used.
 * @param rememberEntities true if active entity actors shall be automatically restarted upon `Shard`
 *   restart. i.e. if the `Shard` is started on a different `ShardRegion` due to rebalance or crash.
 * @param journalPluginId Absolute path to the journal plugin configuration entity that is to
 *   be used for the internal persistence of ClusterSharding. If not defined the default
 *   journal plugin is used. Note that this is not related to persistence used by the entity
 *   actors.
 * @param snapshotPluginId Absolute path to the snapshot plugin configuration entity that is to
 *   be used for the internal persistence of ClusterSharding. If not defined the default
 *   snapshot plugin is used. Note that this is not related to persistence used by the entity
 *   actors.
 * @param passivateIdleEntityAfter Passivate entities that have not received any message in this interval.
 *   Note that only messages sent through sharding are counted, so direct messages
 *   to the `ActorRef` of the actor or messages that it sends to itself are not counted as activity.
 *   Use 0 to disable automatic passivation.
 * @param tuningParameters additional tuning parameters, see descriptions in reference.conf
 */
final class ClusterShardingSettings(
  val role:                         Option[String],
  val rememberEntities:             Boolean,
  val journalPluginId:              String,
  val snapshotPluginId:             String,
  val stateStoreMode:               String,
  val passivateIdleEntityAfter:     FiniteDuration,
  val tuningParameters:             ClusterShardingSettings.TuningParameters,
  val coordinatorSingletonSettings: ClusterSingletonManagerSettings) extends NoSerializationVerificationNeeded {

  // included for binary compatibility reasons
  @deprecated("Use the ClusterShardingSettings factory methods or the constructor including passivateIdleEntityAfter instead", "2.5.18")
  def this(
    role:                         Option[String],
    rememberEntities:             Boolean,
    journalPluginId:              String,
    snapshotPluginId:             String,
    stateStoreMode:               String,
    tuningParameters:             ClusterShardingSettings.TuningParameters,
    coordinatorSingletonSettings: ClusterSingletonManagerSettings) =
    this(role, rememberEntities, journalPluginId, snapshotPluginId, stateStoreMode, Duration.Zero, tuningParameters, coordinatorSingletonSettings)

  import ClusterShardingSettings.{ StateStoreModePersistence, StateStoreModeDData }
  require(
    stateStoreMode == StateStoreModePersistence || stateStoreMode == StateStoreModeDData,
    s"Unknown 'state-store-mode' [$stateStoreMode], valid values are '$StateStoreModeDData' or '$StateStoreModePersistence'")

  /** If true, this node should run the shard region, otherwise just a shard proxy should started on this node. */
  @InternalApi
  private[akka] def shouldHostShard(cluster: Cluster): Boolean =
    role.forall(cluster.selfMember.roles.contains)

  def withRole(role: String): ClusterShardingSettings = copy(role = ClusterShardingSettings.roleOption(role))

  def withRole(role: Option[String]): ClusterShardingSettings = copy(role = role)

  def withRememberEntities(rememberEntities: Boolean): ClusterShardingSettings =
    copy(rememberEntities = rememberEntities)

  def withJournalPluginId(journalPluginId: String): ClusterShardingSettings =
    copy(journalPluginId = journalPluginId)

  def withSnapshotPluginId(snapshotPluginId: String): ClusterShardingSettings =
    copy(snapshotPluginId = snapshotPluginId)

  def withTuningParameters(tuningParameters: ClusterShardingSettings.TuningParameters): ClusterShardingSettings =
    copy(tuningParameters = tuningParameters)

  def withStateStoreMode(stateStoreMode: String): ClusterShardingSettings =
    copy(stateStoreMode = stateStoreMode)

  def withPassivateIdleAfter(duration: FiniteDuration): ClusterShardingSettings =
    copy(passivateIdleAfter = duration)

  def withPassivateIdleAfter(duration: java.time.Duration): ClusterShardingSettings =
    copy(passivateIdleAfter = duration.asScala)

  /**
   * The `role` of the `ClusterSingletonManagerSettings` is not used. The `role` of the
   * coordinator singleton will be the same as the `role` of `ClusterShardingSettings`.
   */
  def withCoordinatorSingletonSettings(coordinatorSingletonSettings: ClusterSingletonManagerSettings): ClusterShardingSettings =
    copy(coordinatorSingletonSettings = coordinatorSingletonSettings)

  private def copy(
    role:                         Option[String]                           = role,
    rememberEntities:             Boolean                                  = rememberEntities,
    journalPluginId:              String                                   = journalPluginId,
    snapshotPluginId:             String                                   = snapshotPluginId,
    stateStoreMode:               String                                   = stateStoreMode,
    passivateIdleAfter:           FiniteDuration                           = passivateIdleEntityAfter,
    tuningParameters:             ClusterShardingSettings.TuningParameters = tuningParameters,
    coordinatorSingletonSettings: ClusterSingletonManagerSettings          = coordinatorSingletonSettings): ClusterShardingSettings =

    new ClusterShardingSettings(
      role,
      rememberEntities,
      journalPluginId,
      snapshotPluginId,
      stateStoreMode,
      passivateIdleAfter,
      tuningParameters,
      coordinatorSingletonSettings)
}
