/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.actor.ActorSystem
import akka.actor.NoSerializationVerificationNeeded
import com.typesafe.config.Config
import akka.cluster.singleton.ClusterSingletonManagerSettings

object ClusterShardingSettings {
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

    new ClusterShardingSettings(
      role = roleOption(config.getString("role")),
      rememberEntities = config.getBoolean("remember-entities"),
      journalPluginId = config.getString("journal-plugin-id"),
      snapshotPluginId = config.getString("snapshot-plugin-id"),
      stateStoreMode = config.getString("state-store-mode"),
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
 * @param tuningParameters additional tuning parameters, see descriptions in reference.conf
 */
final class ClusterShardingSettings(
  val role:                         Option[String],
  val rememberEntities:             Boolean,
  val journalPluginId:              String,
  val snapshotPluginId:             String,
  val stateStoreMode:               String,
  val tuningParameters:             ClusterShardingSettings.TuningParameters,
  val coordinatorSingletonSettings: ClusterSingletonManagerSettings) extends NoSerializationVerificationNeeded {

  require(
    stateStoreMode == "persistence" || stateStoreMode == "ddata",
    s"Unknown 'state-store-mode' [$stateStoreMode], valid values are 'persistence' or 'ddata'")

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
    tuningParameters:             ClusterShardingSettings.TuningParameters = tuningParameters,
    coordinatorSingletonSettings: ClusterSingletonManagerSettings          = coordinatorSingletonSettings): ClusterShardingSettings =
    new ClusterShardingSettings(
      role,
      rememberEntities,
      journalPluginId,
      snapshotPluginId,
      stateStoreMode,
      tuningParameters,
      coordinatorSingletonSettings)
}
