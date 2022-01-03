/*
 * Copyright (C) 2015-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.ActorSystem
import akka.actor.NoSerializationVerificationNeeded
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.cluster.Cluster
import akka.cluster.singleton.ClusterSingletonManagerSettings
import akka.coordination.lease.LeaseUsageSettings
import akka.japi.Util.immutableSeq
import akka.util.Helpers.toRootLowerCase
import akka.util.JavaDurationConverters._
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration._

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

    val passivationStrategySettings = PassivationStrategySettings.fromSharding(config)

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

  /**
   * API MAY CHANGE: Settings for passivation strategies may change after additional testing and feedback.
   */
  @ApiMayChange
  final class PassivationStrategySettings private[akka] (
      val idleEntitySettings: Option[PassivationStrategySettings.IdleSettings],
      val activeEntityLimit: Option[Int],
      val replacementPolicySettings: Option[PassivationStrategySettings.PolicySettings],
      private[akka] val oldSettingUsed: Boolean) {

    def this(
        idleEntitySettings: Option[PassivationStrategySettings.IdleSettings],
        activeEntityLimit: Option[Int],
        replacementPolicySettings: Option[PassivationStrategySettings.PolicySettings]) =
      this(idleEntitySettings, activeEntityLimit, replacementPolicySettings, oldSettingUsed = false)

    import PassivationStrategySettings._

    def withIdleEntityPassivation(settings: IdleSettings): PassivationStrategySettings =
      copy(idleEntitySettings = Some(settings), oldSettingUsed = false)

    def withIdleEntityPassivation(timeout: FiniteDuration): PassivationStrategySettings =
      withIdleEntityPassivation(IdleSettings.defaults.withTimeout(timeout))

    def withIdleEntityPassivation(timeout: FiniteDuration, interval: FiniteDuration): PassivationStrategySettings =
      withIdleEntityPassivation(IdleSettings.defaults.withTimeout(timeout).withInterval(interval))

    def withIdleEntityPassivation(timeout: java.time.Duration): PassivationStrategySettings =
      withIdleEntityPassivation(IdleSettings.defaults.withTimeout(timeout))

    def withIdleEntityPassivation(
        timeout: java.time.Duration,
        interval: java.time.Duration): PassivationStrategySettings =
      withIdleEntityPassivation(IdleSettings.defaults.withTimeout(timeout).withInterval(interval))

    def withActiveEntityLimit(limit: Int): PassivationStrategySettings =
      copy(activeEntityLimit = Some(limit))

    def withReplacementPolicy(settings: PolicySettings): PassivationStrategySettings =
      copy(replacementPolicySettings = Some(settings))

    def withLeastRecentlyUsedReplacement(): PassivationStrategySettings =
      withReplacementPolicy(LeastRecentlyUsedSettings.defaults)

    def withMostRecentlyUsedReplacement(): PassivationStrategySettings =
      withReplacementPolicy(MostRecentlyUsedSettings.defaults)

    def withLeastFrequentlyUsedReplacement(): PassivationStrategySettings =
      withReplacementPolicy(LeastFrequentlyUsedSettings.defaults)

    private[akka] def withOldIdleStrategy(timeout: FiniteDuration): PassivationStrategySettings =
      copy(
        idleEntitySettings = Some(new IdleSettings(timeout, None)),
        activeEntityLimit = None,
        replacementPolicySettings = None,
        oldSettingUsed = true)

    private def copy(
        idleEntitySettings: Option[IdleSettings] = idleEntitySettings,
        activeEntityLimit: Option[Int] = activeEntityLimit,
        replacementPolicySettings: Option[PolicySettings] = replacementPolicySettings,
        oldSettingUsed: Boolean = oldSettingUsed): PassivationStrategySettings =
      new PassivationStrategySettings(idleEntitySettings, activeEntityLimit, replacementPolicySettings, oldSettingUsed)
  }

  /**
   * API MAY CHANGE: Settings for passivation strategies may change after additional testing and feedback.
   */
  @ApiMayChange
  object PassivationStrategySettings {
    val defaults = new PassivationStrategySettings(
      idleEntitySettings = None,
      activeEntityLimit = None,
      replacementPolicySettings = None,
      oldSettingUsed = false)

    val disabled: PassivationStrategySettings = defaults

    object IdleSettings {
      val defaults: IdleSettings = new IdleSettings(timeout = 2.minutes, interval = None)

      def apply(config: Config): IdleSettings = {
        val timeout = config.getDuration("timeout", MILLISECONDS).millis
        val interval =
          if (toRootLowerCase(config.getString("interval")) == "default") None
          else Some(config.getDuration("interval", MILLISECONDS).millis)
        new IdleSettings(timeout, interval)
      }

      def optional(config: Config): Option[IdleSettings] =
        toRootLowerCase(config.getString("timeout")) match {
          case "off" | "none" => None
          case _              => Some(IdleSettings(config))
        }
    }

    final class IdleSettings(val timeout: FiniteDuration, val interval: Option[FiniteDuration]) {

      def withTimeout(timeout: FiniteDuration): IdleSettings = copy(timeout = timeout)

      def withTimeout(timeout: java.time.Duration): IdleSettings = withTimeout(timeout.asScala)

      def withInterval(interval: FiniteDuration): IdleSettings = copy(interval = Some(interval))

      def withInterval(interval: java.time.Duration): IdleSettings = withInterval(interval.asScala)

      private def copy(timeout: FiniteDuration = timeout, interval: Option[FiniteDuration] = interval): IdleSettings =
        new IdleSettings(timeout, interval)
    }

    object PolicySettings {
      def apply(config: Config): PolicySettings =
        toRootLowerCase(config.getString("policy")) match {
          case "least-recently-used"   => LeastRecentlyUsedSettings(config.getConfig("least-recently-used"))
          case "most-recently-used"    => MostRecentlyUsedSettings(config.getConfig("most-recently-used"))
          case "least-frequently-used" => LeastFrequentlyUsedSettings(config.getConfig("least-frequently-used"))
        }

      def optional(config: Config): Option[PolicySettings] =
        toRootLowerCase(config.getString("policy")) match {
          case "off" | "none" => None
          case _              => Some(PolicySettings(config))
        }
    }

    sealed trait PolicySettings

    object LeastRecentlyUsedSettings {
      val defaults: LeastRecentlyUsedSettings = new LeastRecentlyUsedSettings(segmentedSettings = None)

      def apply(config: Config): LeastRecentlyUsedSettings = {
        val segmentedSettings = SegmentedSettings.optional(config.getConfig("segmented"))
        new LeastRecentlyUsedSettings(segmentedSettings)
      }

      object SegmentedSettings {
        def apply(config: Config): SegmentedSettings = {
          val levels = config.getInt("levels")
          val proportions = immutableSeq(config.getDoubleList("proportions")).map(_.toDouble)
          new SegmentedSettings(levels, proportions)
        }

        def optional(config: Config): Option[SegmentedSettings] = {
          toRootLowerCase(config.getString("levels")) match {
            case "off" | "none" => None
            case _              => Some(SegmentedSettings(config))
          }
        }
      }

      final class SegmentedSettings(val levels: Int, val proportions: immutable.Seq[Double]) {

        def withLevels(levels: Int): SegmentedSettings = copy(levels = levels)

        def withProportions(proportions: immutable.Seq[Double]): SegmentedSettings = copy(proportions = proportions)

        def withProportions(proportions: java.util.List[java.lang.Double]): SegmentedSettings =
          copy(proportions = immutableSeq(proportions).map(_.toDouble))

        private def copy(levels: Int = levels, proportions: immutable.Seq[Double] = proportions): SegmentedSettings =
          new SegmentedSettings(levels, proportions)
      }
    }

    final class LeastRecentlyUsedSettings(val segmentedSettings: Option[LeastRecentlyUsedSettings.SegmentedSettings])
        extends PolicySettings {
      import LeastRecentlyUsedSettings.SegmentedSettings

      def withSegmented(levels: Int): LeastRecentlyUsedSettings =
        copy(segmentedSettings = Some(new SegmentedSettings(levels, Nil)))

      def withSegmented(proportions: immutable.Seq[Double]): LeastRecentlyUsedSettings =
        copy(segmentedSettings = Some(new SegmentedSettings(proportions.size, proportions)))

      def withSegmentedProportions(proportions: java.util.List[java.lang.Double]): LeastRecentlyUsedSettings =
        withSegmented(immutableSeq(proportions).map(_.toDouble))

      private def copy(segmentedSettings: Option[SegmentedSettings]): LeastRecentlyUsedSettings =
        new LeastRecentlyUsedSettings(segmentedSettings)
    }

    object MostRecentlyUsedSettings {
      val defaults: MostRecentlyUsedSettings = new MostRecentlyUsedSettings

      def apply(config: Config): MostRecentlyUsedSettings = {
        val _ = config // not used
        new MostRecentlyUsedSettings
      }
    }

    final class MostRecentlyUsedSettings extends PolicySettings

    object LeastFrequentlyUsedSettings {
      val defaults: LeastFrequentlyUsedSettings = new LeastFrequentlyUsedSettings(dynamicAging = false)

      def apply(config: Config): LeastFrequentlyUsedSettings = {
        val dynamicAging = config.getBoolean("dynamic-aging")
        new LeastFrequentlyUsedSettings(dynamicAging)
      }
    }

    final class LeastFrequentlyUsedSettings(val dynamicAging: Boolean) extends PolicySettings {

      def withDynamicAging(): LeastFrequentlyUsedSettings = withDynamicAging(enabled = true)

      def withDynamicAging(enabled: Boolean): LeastFrequentlyUsedSettings = copy(dynamicAging = enabled)

      private def copy(dynamicAging: Boolean): LeastFrequentlyUsedSettings =
        new LeastFrequentlyUsedSettings(dynamicAging)
    }

    /**
     * API MAY CHANGE: Settings and configuration for passivation strategies may change after additional
     * testing and feedback.
     */
    @ApiMayChange
    def apply(config: Config): PassivationStrategySettings = {
      toRootLowerCase(config.getString("strategy")) match {
        case "off" | "none" => PassivationStrategySettings.disabled
        case strategyName =>
          val strategyDefaults = config.getConfig("strategy-defaults")
          val strategyConfig = config.getConfig(strategyName).withFallback(strategyDefaults)
          val idleEntitySettings = IdleSettings.optional(strategyConfig.getConfig("idle-entity"))
          val activeEntityLimit = strategyConfig.getString("active-entity-limit") match {
            case "off" | "none" => None
            case _              => Some(strategyConfig.getInt("active-entity-limit"))
          }
          val replacementPolicySettings = PolicySettings.optional(strategyConfig.getConfig("replacement"))
          new PassivationStrategySettings(idleEntitySettings, activeEntityLimit, replacementPolicySettings)
      }
    }

    def fromSharding(shardingConfig: Config): PassivationStrategySettings = {
      // default to old setting if it exists (defined in application.conf), overriding the new settings
      if (shardingConfig.hasPath("passivate-idle-entity-after")) {
        val timeout =
          if (toRootLowerCase(shardingConfig.getString("passivate-idle-entity-after")) == "off") Duration.Zero
          else shardingConfig.getDuration("passivate-idle-entity-after", MILLISECONDS).millis
        oldDefault(timeout)
      } else {
        PassivationStrategySettings(shardingConfig.getConfig("passivation"))
      }
    }

    private[akka] def oldDefault(idleTimeout: FiniteDuration): PassivationStrategySettings =
      defaults.withOldIdleStrategy(idleTimeout)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] sealed trait PassivationStrategy

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] case object NoPassivationStrategy extends PassivationStrategy

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] object IdlePassivationStrategy {
    def apply(settings: PassivationStrategySettings.IdleSettings): IdlePassivationStrategy =
      IdlePassivationStrategy(settings.timeout, settings.interval.getOrElse(settings.timeout / 2))
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] case class IdlePassivationStrategy(timeout: FiniteDuration, interval: FiniteDuration)
      extends PassivationStrategy

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] object LeastRecentlyUsedPassivationStrategy {
    def apply(
        settings: PassivationStrategySettings.LeastRecentlyUsedSettings,
        limit: Int,
        idle: Option[IdlePassivationStrategy]): LeastRecentlyUsedPassivationStrategy = {
      settings.segmentedSettings match {
        case Some(segmented) =>
          val proportions =
            if (segmented.levels < 2) Nil
            else if (segmented.proportions.isEmpty) List.fill(segmented.levels)(1.0 / segmented.levels)
            else segmented.proportions
          LeastRecentlyUsedPassivationStrategy(limit, proportions, idle)
        case _ => LeastRecentlyUsedPassivationStrategy(limit, Nil, idle)
      }
    }
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] case class LeastRecentlyUsedPassivationStrategy(
      limit: Int,
      segmented: immutable.Seq[Double],
      idle: Option[IdlePassivationStrategy])
      extends PassivationStrategy

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] case class MostRecentlyUsedPassivationStrategy(limit: Int, idle: Option[IdlePassivationStrategy])
      extends PassivationStrategy

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] object LeastFrequentlyUsedPassivationStrategy {
    def apply(
        settings: PassivationStrategySettings.LeastFrequentlyUsedSettings,
        limit: Int,
        idle: Option[IdlePassivationStrategy]): LeastFrequentlyUsedPassivationStrategy =
      LeastFrequentlyUsedPassivationStrategy(limit, settings.dynamicAging, idle)
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] case class LeastFrequentlyUsedPassivationStrategy(
      limit: Int,
      dynamicAging: Boolean,
      idle: Option[IdlePassivationStrategy])
      extends PassivationStrategy

  /**
   * INTERNAL API
   * Determine the passivation strategy to use from settings.
   */
  @InternalApi
  private[akka] object PassivationStrategy {
    def apply(settings: ClusterShardingSettings): PassivationStrategy =
      if (settings.rememberEntities) {
        NoPassivationStrategy
      } else {
        val idle = settings.passivationStrategySettings.idleEntitySettings match {
          case Some(idleSettings) if idleSettings.timeout > Duration.Zero => Some(IdlePassivationStrategy(idleSettings))
          case _                                                          => None
        }
        settings.passivationStrategySettings.activeEntityLimit match {
          case Some(limit) =>
            settings.passivationStrategySettings.replacementPolicySettings match {
              case Some(settings: PassivationStrategySettings.LeastRecentlyUsedSettings) =>
                LeastRecentlyUsedPassivationStrategy(settings, limit, idle)
              case Some(_: PassivationStrategySettings.MostRecentlyUsedSettings) =>
                MostRecentlyUsedPassivationStrategy(limit, idle)
              case Some(settings: PassivationStrategySettings.LeastFrequentlyUsedSettings) =>
                LeastFrequentlyUsedPassivationStrategy(settings, limit, idle)
              case _ => idle.getOrElse(NoPassivationStrategy)
            }
          case _ => idle.getOrElse(NoPassivationStrategy)
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

  @deprecated("See passivationStrategySettings.idleEntitySettings instead", since = "2.6.18")
  def passivateIdleEntityAfter: FiniteDuration =
    passivationStrategySettings.idleEntitySettings.fold(Duration.Zero)(_.timeout)

  @deprecated("Use withPassivationStrategy instead", since = "2.6.18")
  def withPassivateIdleAfter(duration: FiniteDuration): ClusterShardingSettings =
    copy(passivationStrategySettings = passivationStrategySettings.withOldIdleStrategy(duration))

  @deprecated("Use withPassivationStrategy instead", since = "2.6.18")
  def withPassivateIdleAfter(duration: java.time.Duration): ClusterShardingSettings =
    copy(passivationStrategySettings = passivationStrategySettings.withOldIdleStrategy(duration.asScala))

  /**
   * API MAY CHANGE: Settings for passivation strategies may change after additional testing and feedback.
   */
  @ApiMayChange
  def withPassivationStrategy(settings: ClusterShardingSettings.PassivationStrategySettings): ClusterShardingSettings =
    copy(passivationStrategySettings = settings)

  def withNoPassivationStrategy(): ClusterShardingSettings =
    copy(passivationStrategySettings = ClusterShardingSettings.PassivationStrategySettings.disabled)

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
