/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
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
      entryRestartBackoff = config.getDuration("entry-restart-backoff", MILLISECONDS).millis,
      rebalanceInterval = config.getDuration("rebalance-interval", MILLISECONDS).millis,
      snapshotAfter = config.getInt("snapshot-after"),
      leastShardAllocationRebalanceThreshold =
        config.getInt("least-shard-allocation-strategy.rebalance-threshold"),
      leastShardAllocationMaxSimultaneousRebalance =
        config.getInt("least-shard-allocation-strategy.max-simultaneous-rebalance"))

    val coordinatorSingletonSettings = ClusterSingletonManagerSettings(config.getConfig("coordinator-singleton"))

    new ClusterShardingSettings(
      role = roleOption(config.getString("role")),
      rememberEntries = config.getBoolean("remember-entries"),
      journalPluginId = config.getString("journal-plugin-id"),
      snapshotPluginId = config.getString("snapshot-plugin-id"),
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
    val coordinatorFailureBackoff: FiniteDuration,
    val retryInterval: FiniteDuration,
    val bufferSize: Int,
    val handOffTimeout: FiniteDuration,
    val shardStartTimeout: FiniteDuration,
    val shardFailureBackoff: FiniteDuration,
    val entryRestartBackoff: FiniteDuration,
    val rebalanceInterval: FiniteDuration,
    val snapshotAfter: Int,
    val leastShardAllocationRebalanceThreshold: Int,
    val leastShardAllocationMaxSimultaneousRebalance: Int)
}

/**
 * @param role specifies that this entry type requires cluster nodes with a specific role.
 *   If the role is not specified all nodes in the cluster are used.
 * @param rememberEntries true if active entry actors shall be automatically restarted upon `Shard`
 *   restart. i.e. if the `Shard` is started on a different `ShardRegion` due to rebalance or crash.
 * @param journalPluginId Absolute path to the journal plugin configuration entry that is to
 *   be used for the internal persistence of ClusterSharding. If not defined the default
 *   journal plugin is used. Note that this is not related to persistence used by the entry
 *   actors.
 * @param snapshotPluginId Absolute path to the snapshot plugin configuration entry that is to
 *   be used for the internal persistence of ClusterSharding. If not defined the default
 *   snapshot plugin is used. Note that this is not related to persistence used by the entry
 *   actors.
 * @param tuningParameters additional tuning parameters, see descriptions in reference.conf
 */
final class ClusterShardingSettings(
  val role: Option[String],
  val rememberEntries: Boolean,
  val journalPluginId: String,
  val snapshotPluginId: String,
  val tuningParameters: ClusterShardingSettings.TuningParameters,
  val coordinatorSingletonSettings: ClusterSingletonManagerSettings) extends NoSerializationVerificationNeeded {

  def withRole(role: String): ClusterShardingSettings = copy(role = ClusterShardingSettings.roleOption(role))

  def withRole(role: Option[String]): ClusterShardingSettings = copy(role = role)

  def withRememberEntries(rememberEntries: Boolean): ClusterShardingSettings =
    copy(rememberEntries = rememberEntries)

  def withJournalPluginId(journalPluginId: String): ClusterShardingSettings =
    copy(journalPluginId = journalPluginId)

  def withSnapshotPluginId(snapshotPluginId: String): ClusterShardingSettings =
    copy(snapshotPluginId = snapshotPluginId)

  def withTuningParameters(tuningParameters: ClusterShardingSettings.TuningParameters): ClusterShardingSettings =
    copy(tuningParameters = tuningParameters)

  def withCoordinatorSingletonSettings(coordinatorSingletonSettings: ClusterSingletonManagerSettings): ClusterShardingSettings =
    copy(coordinatorSingletonSettings = coordinatorSingletonSettings)

  private def copy(role: Option[String] = role,
                   rememberEntries: Boolean = rememberEntries,
                   journalPluginId: String = journalPluginId,
                   snapshotPluginId: String = snapshotPluginId,
                   tuningParameters: ClusterShardingSettings.TuningParameters = tuningParameters,
                   coordinatorSingletonSettings: ClusterSingletonManagerSettings = coordinatorSingletonSettings): ClusterShardingSettings =
    new ClusterShardingSettings(
      role,
      rememberEntries,
      journalPluginId,
      snapshotPluginId,
      tuningParameters,
      coordinatorSingletonSettings)
}
