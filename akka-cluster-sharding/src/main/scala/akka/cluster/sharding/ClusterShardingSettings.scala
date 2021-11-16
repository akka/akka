/*
 * Copyright (C) 2015-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

import com.typesafe.config.Config

import akka.actor.ActorSystem
import akka.actor.NoSerializationVerificationNeeded
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.cluster.Cluster
import akka.cluster.singleton.ClusterSingletonManagerSettings
import akka.coordination.lease.LeaseUsageSettings
import akka.util.Helpers.toRootLowerCase
import akka.util.JavaDurationConverters._

object ClusterShardingSettings {

  val StateStoreModePersistence = "persistence"
  val StateStoreModeDData = "ddata"

  /**
   * Only for testing
   * INTERNAL API
   */
  @InternalApi
  private[akka] val RememberEntitiesStoreCustom = "custom"

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] val RememberEntitiesStoreDData = "ddata"

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] val RememberEntitiesStoreEventsourced = "eventsourced"

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

    def configMajorityPlus(p: String): Int = {
      toRootLowerCase(config.getString(p)) match {
        case "all" => Int.MaxValue
        case _     => config.getInt(p)
      }
    }

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
      leastShardAllocationRebalanceThreshold = config.getInt("least-shard-allocation-strategy.rebalance-threshold"),
      leastShardAllocationMaxSimultaneousRebalance =
        config.getInt("least-shard-allocation-strategy.max-simultaneous-rebalance"),
      waitingForStateTimeout = config.getDuration("waiting-for-state-timeout", MILLISECONDS).millis,
      updatingStateTimeout = config.getDuration("updating-state-timeout", MILLISECONDS).millis,
      entityRecoveryStrategy = config.getString("entity-recovery-strategy"),
      entityRecoveryConstantRateStrategyFrequency =
        config.getDuration("entity-recovery-constant-rate-strategy.frequency", MILLISECONDS).millis,
      entityRecoveryConstantRateStrategyNumberOfEntities =
        config.getInt("entity-recovery-constant-rate-strategy.number-of-entities"),
      coordinatorStateWriteMajorityPlus = configMajorityPlus("coordinator-state.write-majority-plus"),
      coordinatorStateReadMajorityPlus = configMajorityPlus("coordinator-state.read-majority-plus"),
      leastShardAllocationAbsoluteLimit = config.getInt("least-shard-allocation-strategy.rebalance-absolute-limit"),
      leastShardAllocationRelativeLimit = config.getDouble("least-shard-allocation-strategy.rebalance-relative-limit"))

    val coordinatorSingletonSettings = ClusterSingletonManagerSettings(config.getConfig("coordinator-singleton"))

    val passivationStrategySettings = PassivationStrategySettings(config)

    val lease = config.getString("use-lease") match {
      case s if s.isEmpty => None
      case other          => Some(new LeaseUsageSettings(other, config.getDuration("lease-retry-interval").asScala))
    }

    new ClusterShardingSettings(
      role = roleOption(config.getString("role")),
      rememberEntities = config.getBoolean("remember-entities"),
      journalPluginId = config.getString("journal-plugin-id"),
      snapshotPluginId = config.getString("snapshot-plugin-id"),
      stateStoreMode = config.getString("state-store-mode"),
      rememberEntitiesStore = config.getString("remember-entities-store"),
      passivationStrategySettings = passivationStrategySettings,
      shardRegionQueryTimeout = config.getDuration("shard-region-query-timeout", MILLISECONDS).millis,
      tuningParameters,
      coordinatorSingletonSettings,
      lease)
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

  @ApiMayChange
  final class PassivationStrategySettings private (
      val strategy: String,
      val idleTimeout: FiniteDuration,
      val leastRecentlyUsedLimit: Int,
      val mostRecentlyUsedLimit: Int,
      private[akka] val oldSettingUsed: Boolean) {

    def this(strategy: String, idleTimeout: FiniteDuration, leastRecentlyUsedLimit: Int, mostRecentlyUsedLimit: Int) =
      this(strategy, idleTimeout, leastRecentlyUsedLimit, mostRecentlyUsedLimit, oldSettingUsed = false)

    def withIdleStrategy(timeout: FiniteDuration): PassivationStrategySettings =
      copy(strategy = "idle", idleTimeout = timeout, oldSettingUsed = false)

    def withLeastRecentlyUsedStrategy(limit: Int): PassivationStrategySettings =
      copy(strategy = "least-recently-used", leastRecentlyUsedLimit = limit)

    def withMostRecentlyUsedStrategy(limit: Int): PassivationStrategySettings =
      copy(strategy = "most-recently-used", mostRecentlyUsedLimit = limit)

    private[akka] def withOldIdleStrategy(timeout: FiniteDuration): PassivationStrategySettings =
      copy(strategy = "idle", idleTimeout = timeout, oldSettingUsed = true)

    private def copy(
        strategy: String,
        idleTimeout: FiniteDuration = idleTimeout,
        leastRecentlyUsedLimit: Int = leastRecentlyUsedLimit,
        mostRecentlyUsedLimit: Int = mostRecentlyUsedLimit,
        oldSettingUsed: Boolean = oldSettingUsed): PassivationStrategySettings =
      new PassivationStrategySettings(
        strategy,
        idleTimeout,
        leastRecentlyUsedLimit,
        mostRecentlyUsedLimit,
        oldSettingUsed)
  }

  object PassivationStrategySettings {
    val disabled = new PassivationStrategySettings(
      strategy = "none",
      idleTimeout = Duration.Zero,
      leastRecentlyUsedLimit = 0,
      mostRecentlyUsedLimit = 0,
      oldSettingUsed = false)

    def apply(config: Config): PassivationStrategySettings = {
      val settings = new PassivationStrategySettings(
        strategy = toRootLowerCase(config.getString("passivation.strategy")),
        idleTimeout = config.getDuration("passivation.idle.timeout", MILLISECONDS).millis,
        leastRecentlyUsedLimit = config.getInt("passivation.least-recently-used.limit"),
        mostRecentlyUsedLimit = config.getInt("passivation.most-recently-used.limit"))
      // default to old setting if it exists (defined in application.conf), overriding the new settings
      if (config.hasPath("passivate-idle-entity-after")) {
        val timeout =
          if (toRootLowerCase(config.getString("passivate-idle-entity-after")) == "off") Duration.Zero
          else config.getDuration("passivate-idle-entity-after", MILLISECONDS).millis
        settings.withOldIdleStrategy(timeout)
      } else {
        settings
      }
    }

    private[akka] def oldDefault(idleTimeout: FiniteDuration): PassivationStrategySettings =
      disabled.withOldIdleStrategy(idleTimeout)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] sealed trait PassivationStrategy
  private[akka] case object NoPassivationStrategy extends PassivationStrategy
  private[akka] case class IdlePassivationStrategy(timeout: FiniteDuration) extends PassivationStrategy
  private[akka] case class LeastRecentlyUsedPassivationStrategy(limit: Int) extends PassivationStrategy
  private[akka] case class MostRecentlyUsedPassivationStrategy(limit: Int) extends PassivationStrategy

  /**
   * INTERNAL API
   * Determine the passivation strategy to use from settings.
   */
  @InternalApi
  private[akka] object PassivationStrategy {
    def apply(settings: ClusterShardingSettings): PassivationStrategy = {
      if (settings.rememberEntities) {
        NoPassivationStrategy
      } else
        settings.passivationStrategySettings.strategy match {
          case "idle" if settings.passivationStrategySettings.idleTimeout > Duration.Zero =>
            IdlePassivationStrategy(settings.passivationStrategySettings.idleTimeout)
          case "least-recently-used" =>
            LeastRecentlyUsedPassivationStrategy(settings.passivationStrategySettings.leastRecentlyUsedLimit)
          case "most-recently-used" =>
            MostRecentlyUsedPassivationStrategy(settings.passivationStrategySettings.mostRecentlyUsedLimit)
          case _ => NoPassivationStrategy
        }
    }
  }

  class TuningParameters(
      val coordinatorFailureBackoff: FiniteDuration,
      val retryInterval: FiniteDuration,
      val bufferSize: Int,
      val handOffTimeout: FiniteDuration,
      val shardStartTimeout: FiniteDuration,
      val shardFailureBackoff: FiniteDuration,
      val entityRestartBackoff: FiniteDuration,
      val rebalanceInterval: FiniteDuration,
      val snapshotAfter: Int,
      val keepNrOfBatches: Int,
      val leastShardAllocationRebalanceThreshold: Int,
      val leastShardAllocationMaxSimultaneousRebalance: Int,
      val waitingForStateTimeout: FiniteDuration,
      val updatingStateTimeout: FiniteDuration,
      val entityRecoveryStrategy: String,
      val entityRecoveryConstantRateStrategyFrequency: FiniteDuration,
      val entityRecoveryConstantRateStrategyNumberOfEntities: Int,
      val coordinatorStateWriteMajorityPlus: Int,
      val coordinatorStateReadMajorityPlus: Int,
      val leastShardAllocationAbsoluteLimit: Int,
      val leastShardAllocationRelativeLimit: Double) {

    require(
      entityRecoveryStrategy == "all" || entityRecoveryStrategy == "constant",
      s"Unknown 'entity-recovery-strategy' [$entityRecoveryStrategy], valid values are 'all' or 'constant'")

    // included for binary compatibility
    @deprecated(
      "Use the ClusterShardingSettings factory methods or the constructor including " +
      "leastShardAllocationAbsoluteLimit and leastShardAllocationRelativeLimit instead",
      since = "2.6.10")
    def this(
        coordinatorFailureBackoff: FiniteDuration,
        retryInterval: FiniteDuration,
        bufferSize: Int,
        handOffTimeout: FiniteDuration,
        shardStartTimeout: FiniteDuration,
        shardFailureBackoff: FiniteDuration,
        entityRestartBackoff: FiniteDuration,
        rebalanceInterval: FiniteDuration,
        snapshotAfter: Int,
        keepNrOfBatches: Int,
        leastShardAllocationRebalanceThreshold: Int,
        leastShardAllocationMaxSimultaneousRebalance: Int,
        waitingForStateTimeout: FiniteDuration,
        updatingStateTimeout: FiniteDuration,
        entityRecoveryStrategy: String,
        entityRecoveryConstantRateStrategyFrequency: FiniteDuration,
        entityRecoveryConstantRateStrategyNumberOfEntities: Int,
        coordinatorStateWriteMajorityPlus: Int,
        coordinatorStateReadMajorityPlus: Int) =
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
        keepNrOfBatches,
        leastShardAllocationRebalanceThreshold,
        leastShardAllocationMaxSimultaneousRebalance,
        waitingForStateTimeout,
        updatingStateTimeout,
        entityRecoveryStrategy,
        entityRecoveryConstantRateStrategyFrequency,
        entityRecoveryConstantRateStrategyNumberOfEntities,
        coordinatorStateWriteMajorityPlus,
        coordinatorStateReadMajorityPlus,
        leastShardAllocationAbsoluteLimit = 100,
        leastShardAllocationRelativeLimit = 0.1)

    // included for binary compatibility
    @deprecated(
      "Use the ClusterShardingSettings factory methods or the constructor including " +
      "coordinatorStateWriteMajorityPlus and coordinatorStateReadMajorityPlus instead",
      since = "2.6.5")
    def this(
        coordinatorFailureBackoff: FiniteDuration,
        retryInterval: FiniteDuration,
        bufferSize: Int,
        handOffTimeout: FiniteDuration,
        shardStartTimeout: FiniteDuration,
        shardFailureBackoff: FiniteDuration,
        entityRestartBackoff: FiniteDuration,
        rebalanceInterval: FiniteDuration,
        snapshotAfter: Int,
        keepNrOfBatches: Int,
        leastShardAllocationRebalanceThreshold: Int,
        leastShardAllocationMaxSimultaneousRebalance: Int,
        waitingForStateTimeout: FiniteDuration,
        updatingStateTimeout: FiniteDuration,
        entityRecoveryStrategy: String,
        entityRecoveryConstantRateStrategyFrequency: FiniteDuration,
        entityRecoveryConstantRateStrategyNumberOfEntities: Int) =
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
        keepNrOfBatches,
        leastShardAllocationRebalanceThreshold,
        leastShardAllocationMaxSimultaneousRebalance,
        waitingForStateTimeout,
        updatingStateTimeout,
        entityRecoveryStrategy,
        entityRecoveryConstantRateStrategyFrequency,
        entityRecoveryConstantRateStrategyNumberOfEntities,
        coordinatorStateWriteMajorityPlus = 5,
        coordinatorStateReadMajorityPlus = 5)

    // included for binary compatibility
    @deprecated("Use the ClusterShardingSettings factory methods or the full constructor instead", since = "2.6.5")
    def this(
        coordinatorFailureBackoff: FiniteDuration,
        retryInterval: FiniteDuration,
        bufferSize: Int,
        handOffTimeout: FiniteDuration,
        shardStartTimeout: FiniteDuration,
        shardFailureBackoff: FiniteDuration,
        entityRestartBackoff: FiniteDuration,
        rebalanceInterval: FiniteDuration,
        snapshotAfter: Int,
        leastShardAllocationRebalanceThreshold: Int,
        leastShardAllocationMaxSimultaneousRebalance: Int,
        waitingForStateTimeout: FiniteDuration,
        updatingStateTimeout: FiniteDuration,
        entityRecoveryStrategy: String,
        entityRecoveryConstantRateStrategyFrequency: FiniteDuration,
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
    @deprecated("Use the ClusterShardingSettings factory methods or the full constructor instead", since = "2.6.5")
    def this(
        coordinatorFailureBackoff: FiniteDuration,
        retryInterval: FiniteDuration,
        bufferSize: Int,
        handOffTimeout: FiniteDuration,
        shardStartTimeout: FiniteDuration,
        shardFailureBackoff: FiniteDuration,
        entityRestartBackoff: FiniteDuration,
        rebalanceInterval: FiniteDuration,
        snapshotAfter: Int,
        leastShardAllocationRebalanceThreshold: Int,
        leastShardAllocationMaxSimultaneousRebalance: Int,
        waitingForStateTimeout: FiniteDuration,
        updatingStateTimeout: FiniteDuration) = {
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
 * @param passivationStrategySettings settings for automatic passivation strategy, see descriptions in reference.conf
 * @param tuningParameters additional tuning parameters, see descriptions in reference.conf
 * @param shardRegionQueryTimeout the timeout for querying a shard region, see descriptions in reference.conf
 */
final class ClusterShardingSettings(
    val role: Option[String],
    val rememberEntities: Boolean,
    val journalPluginId: String,
    val snapshotPluginId: String,
    val stateStoreMode: String,
    val rememberEntitiesStore: String,
    val passivationStrategySettings: ClusterShardingSettings.PassivationStrategySettings,
    val shardRegionQueryTimeout: FiniteDuration,
    val tuningParameters: ClusterShardingSettings.TuningParameters,
    val coordinatorSingletonSettings: ClusterSingletonManagerSettings,
    val leaseSettings: Option[LeaseUsageSettings])
    extends NoSerializationVerificationNeeded {

  @deprecated(
    "Use the ClusterShardingSettings factory methods or the constructor including passivationStrategySettings instead",
    "2.6.18")
  def this(
      role: Option[String],
      rememberEntities: Boolean,
      journalPluginId: String,
      snapshotPluginId: String,
      stateStoreMode: String,
      rememberEntitiesStore: String,
      passivateIdleEntityAfter: FiniteDuration,
      shardRegionQueryTimeout: FiniteDuration,
      tuningParameters: ClusterShardingSettings.TuningParameters,
      coordinatorSingletonSettings: ClusterSingletonManagerSettings,
      leaseSettings: Option[LeaseUsageSettings]) =
    this(
      role,
      rememberEntities,
      journalPluginId,
      snapshotPluginId,
      stateStoreMode,
      rememberEntitiesStore,
      ClusterShardingSettings.PassivationStrategySettings.oldDefault(passivateIdleEntityAfter),
      shardRegionQueryTimeout,
      tuningParameters,
      coordinatorSingletonSettings,
      leaseSettings)

  @deprecated(
    "Use the ClusterShardingSettings factory methods or the constructor including rememberedEntitiesStore instead",
    "2.6.7")
  def this(
      role: Option[String],
      rememberEntities: Boolean,
      journalPluginId: String,
      snapshotPluginId: String,
      stateStoreMode: String,
      passivateIdleEntityAfter: FiniteDuration,
      shardRegionQueryTimeout: FiniteDuration,
      tuningParameters: ClusterShardingSettings.TuningParameters,
      coordinatorSingletonSettings: ClusterSingletonManagerSettings,
      leaseSettings: Option[LeaseUsageSettings]) =
    this(
      role,
      rememberEntities,
      journalPluginId,
      snapshotPluginId,
      stateStoreMode,
      "ddata",
      passivateIdleEntityAfter,
      shardRegionQueryTimeout,
      tuningParameters,
      coordinatorSingletonSettings,
      leaseSettings)

  // bin compat for 2.5.23
  @deprecated(
    "Use the ClusterShardingSettings factory methods or the constructor including shardRegionQueryTimeout instead",
    since = "2.6.0")
  def this(
      role: Option[String],
      rememberEntities: Boolean,
      journalPluginId: String,
      snapshotPluginId: String,
      stateStoreMode: String,
      passivateIdleEntityAfter: FiniteDuration,
      tuningParameters: ClusterShardingSettings.TuningParameters,
      coordinatorSingletonSettings: ClusterSingletonManagerSettings,
      leaseSettings: Option[LeaseUsageSettings]) =
    this(
      role,
      rememberEntities,
      journalPluginId,
      snapshotPluginId,
      stateStoreMode,
      passivateIdleEntityAfter,
      3.seconds,
      tuningParameters,
      coordinatorSingletonSettings,
      leaseSettings)

  // bin compat for 2.5.21
  @deprecated(
    "Use the ClusterShardingSettings factory methods or the constructor including shardRegionQueryTimeout instead",
    since = "2.5.21")
  def this(
      role: Option[String],
      rememberEntities: Boolean,
      journalPluginId: String,
      snapshotPluginId: String,
      stateStoreMode: String,
      passivateIdleEntityAfter: FiniteDuration,
      tuningParameters: ClusterShardingSettings.TuningParameters,
      coordinatorSingletonSettings: ClusterSingletonManagerSettings) =
    this(
      role,
      rememberEntities,
      journalPluginId,
      snapshotPluginId,
      stateStoreMode,
      passivateIdleEntityAfter,
      3.seconds,
      tuningParameters,
      coordinatorSingletonSettings,
      None)

  // included for binary compatibility reasons
  @deprecated(
    "Use the ClusterShardingSettings factory methods or the constructor including passivateIdleEntityAfter instead",
    since = "2.5.18")
  def this(
      role: Option[String],
      rememberEntities: Boolean,
      journalPluginId: String,
      snapshotPluginId: String,
      stateStoreMode: String,
      tuningParameters: ClusterShardingSettings.TuningParameters,
      coordinatorSingletonSettings: ClusterSingletonManagerSettings) =
    this(
      role,
      rememberEntities,
      journalPluginId,
      snapshotPluginId,
      stateStoreMode,
      Duration.Zero,
      tuningParameters,
      coordinatorSingletonSettings)

  import ClusterShardingSettings.{ RememberEntitiesStoreCustom, StateStoreModeDData, StateStoreModePersistence }
  require(
    stateStoreMode == StateStoreModePersistence || stateStoreMode == StateStoreModeDData || stateStoreMode == RememberEntitiesStoreCustom,
    s"Unknown 'state-store-mode' [$stateStoreMode], valid values are '$StateStoreModeDData' or '$StateStoreModePersistence'")

  /** If true, this node should run the shard region, otherwise just a shard proxy should started on this node. */
  @InternalApi
  private[akka] def shouldHostShard(cluster: Cluster): Boolean =
    role.forall(cluster.selfMember.roles.contains)

  @InternalApi
  private[akka] val passivationStrategy: ClusterShardingSettings.PassivationStrategy =
    ClusterShardingSettings.PassivationStrategy(this)

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

  @deprecated("See passivationStrategySettings.idleTimeout instead", since = "2.6.18")
  def passivateIdleEntityAfter: FiniteDuration = passivationStrategySettings.idleTimeout

  @deprecated("Use withIdlePassivationStrategy instead", since = "2.6.18")
  def withPassivateIdleAfter(duration: FiniteDuration): ClusterShardingSettings =
    copy(passivationStrategySettings = passivationStrategySettings.withOldIdleStrategy(duration))

  @deprecated("Use withIdlePassivationStrategy instead", since = "2.6.18")
  def withPassivateIdleAfter(duration: java.time.Duration): ClusterShardingSettings =
    copy(passivationStrategySettings = passivationStrategySettings.withOldIdleStrategy(duration.asScala))

  def withIdlePassivationStrategy(timeout: FiniteDuration): ClusterShardingSettings =
    copy(passivationStrategySettings = passivationStrategySettings.withIdleStrategy(timeout))

  def withIdlePassivationStrategy(timeout: java.time.Duration): ClusterShardingSettings =
    withIdlePassivationStrategy(timeout.asScala)

  def withLeastRecentlyUsedPassivationStrategy(limit: Int): ClusterShardingSettings =
    copy(passivationStrategySettings = passivationStrategySettings.withLeastRecentlyUsedStrategy(limit))

  def withMostRecentlyUsedPassivationStrategy(limit: Int): ClusterShardingSettings =
    copy(passivationStrategySettings = passivationStrategySettings.withMostRecentlyUsedStrategy(limit))

  def withShardRegionQueryTimeout(duration: FiniteDuration): ClusterShardingSettings =
    copy(shardRegionQueryTimeout = duration)

  def withShardRegionQueryTimeout(duration: java.time.Duration): ClusterShardingSettings =
    copy(shardRegionQueryTimeout = duration.asScala)

  def withLeaseSettings(leaseSettings: LeaseUsageSettings): ClusterShardingSettings =
    copy(leaseSettings = Some(leaseSettings))

  /**
   * The `role` of the `ClusterSingletonManagerSettings` is not used. The `role` of the
   * coordinator singleton will be the same as the `role` of `ClusterShardingSettings`.
   */
  def withCoordinatorSingletonSettings(
      coordinatorSingletonSettings: ClusterSingletonManagerSettings): ClusterShardingSettings =
    copy(coordinatorSingletonSettings = coordinatorSingletonSettings)

  private def copy(
      role: Option[String] = role,
      rememberEntities: Boolean = rememberEntities,
      journalPluginId: String = journalPluginId,
      snapshotPluginId: String = snapshotPluginId,
      stateStoreMode: String = stateStoreMode,
      passivationStrategySettings: ClusterShardingSettings.PassivationStrategySettings = passivationStrategySettings,
      shardRegionQueryTimeout: FiniteDuration = shardRegionQueryTimeout,
      tuningParameters: ClusterShardingSettings.TuningParameters = tuningParameters,
      coordinatorSingletonSettings: ClusterSingletonManagerSettings = coordinatorSingletonSettings,
      leaseSettings: Option[LeaseUsageSettings] = leaseSettings): ClusterShardingSettings =
    new ClusterShardingSettings(
      role,
      rememberEntities,
      journalPluginId,
      snapshotPluginId,
      stateStoreMode,
      rememberEntitiesStore,
      passivationStrategySettings,
      shardRegionQueryTimeout,
      tuningParameters,
      coordinatorSingletonSettings,
      leaseSettings)
}
