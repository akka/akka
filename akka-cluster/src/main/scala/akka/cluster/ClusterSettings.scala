/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.collection.immutable
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject

import scala.concurrent.duration.Duration
import akka.actor.Address
import akka.actor.AddressFromURIString
import akka.annotation.InternalApi
import akka.util.Helpers.{ toRootLowerCase, ConfigOps, Requiring }

import scala.concurrent.duration.FiniteDuration
import akka.japi.Util.immutableSeq

object ClusterSettings {
  type DataCenter = String

  /**
   * INTERNAL API.
   */
  @InternalApi
  private[akka] val DcRolePrefix = "dc-"

  /**
   * INTERNAL API.
   */
  @InternalApi
  private[akka] val DefaultDataCenter: DataCenter = "default"

}

final class ClusterSettings(val config: Config, val systemName: String) {
  import ClusterSettings._
  private val cc = config.getConfig("akka.cluster")

  val LogInfoVerbose: Boolean = cc.getBoolean("log-info-verbose")
  val LogInfo: Boolean = LogInfoVerbose || cc.getBoolean("log-info")
  val FailureDetectorConfig: Config = cc.getConfig("failure-detector")
  val FailureDetectorImplementationClass: String = FailureDetectorConfig.getString("implementation-class")
  val HeartbeatInterval: FiniteDuration = {
    FailureDetectorConfig.getMillisDuration("heartbeat-interval")
  }.requiring(_ > Duration.Zero, "failure-detector.heartbeat-interval must be > 0")
  val HeartbeatExpectedResponseAfter: FiniteDuration = {
    FailureDetectorConfig.getMillisDuration("expected-response-after")
  }.requiring(_ > Duration.Zero, "failure-detector.expected-response-after > 0")
  val MonitoredByNrOfMembers: Int = {
    FailureDetectorConfig.getInt("monitored-by-nr-of-members")
  }.requiring(_ > 0, "failure-detector.monitored-by-nr-of-members must be > 0")

  final class CrossDcFailureDetectorSettings(val config: Config) {
    val ImplementationClass: String = config.getString("implementation-class")
    val HeartbeatInterval: FiniteDuration = {
      config.getMillisDuration("heartbeat-interval")
    }.requiring(_ > Duration.Zero, "failure-detector.heartbeat-interval must be > 0")
    val HeartbeatExpectedResponseAfter: FiniteDuration = {
      config.getMillisDuration("expected-response-after")
    }.requiring(_ > Duration.Zero, "failure-detector.expected-response-after > 0")
    def NrOfMonitoringActors: Int = MultiDataCenter.CrossDcConnections
  }

  object MultiDataCenter {
    val CrossDcConnections: Int = cc
      .getInt("multi-data-center.cross-data-center-connections")
      .requiring(_ > 0, "cross-data-center-connections must be > 0")

    val CrossDcGossipProbability: Double = cc
      .getDouble("multi-data-center.cross-data-center-gossip-probability")
      .requiring(d => d >= 0.0d && d <= 1.0d, "cross-data-center-gossip-probability must be >= 0.0 and <= 1.0")

    val CrossDcFailureDetectorSettings: CrossDcFailureDetectorSettings =
      new CrossDcFailureDetectorSettings(cc.getConfig("multi-data-center.failure-detector"))
  }

  val SeedNodes: immutable.IndexedSeq[Address] =
    immutableSeq(cc.getStringList("seed-nodes")).map { case AddressFromURIString(address) => address }.toVector
  val SeedNodeTimeout: FiniteDuration = cc.getMillisDuration("seed-node-timeout")
  val RetryUnsuccessfulJoinAfter: Duration = {
    val key = "retry-unsuccessful-join-after"
    toRootLowerCase(cc.getString(key)) match {
      case "off" => Duration.Undefined
      case _     => cc.getMillisDuration(key).requiring(_ > Duration.Zero, key + " > 0s, or off")
    }
  }
  val ShutdownAfterUnsuccessfulJoinSeedNodes: Duration = {
    val key = "shutdown-after-unsuccessful-join-seed-nodes"
    toRootLowerCase(cc.getString(key)) match {
      case "off" => Duration.Undefined
      case _     => cc.getMillisDuration(key).requiring(_ > Duration.Zero, key + " > 0s, or off")
    }
  }
  val PeriodicTasksInitialDelay: FiniteDuration = cc.getMillisDuration("periodic-tasks-initial-delay")
  val GossipInterval: FiniteDuration = cc.getMillisDuration("gossip-interval")
  val GossipTimeToLive: FiniteDuration = {
    cc.getMillisDuration("gossip-time-to-live")
  }.requiring(_ > Duration.Zero, "gossip-time-to-live must be > 0")
  val LeaderActionsInterval: FiniteDuration = cc.getMillisDuration("leader-actions-interval")
  val UnreachableNodesReaperInterval: FiniteDuration = cc.getMillisDuration("unreachable-nodes-reaper-interval")
  val PublishStatsInterval: Duration = {
    val key = "publish-stats-interval"
    toRootLowerCase(cc.getString(key)) match {
      case "off" => Duration.Undefined
      case _     => cc.getMillisDuration(key).requiring(_ >= Duration.Zero, key + " >= 0s, or off")
    }
  }

  /**
   * Is in fact always a `FiniteDuration` but needs to stay `Duration` for binary compatibility
   */
  val PruneGossipTombstonesAfter: Duration = {
    val key = "prune-gossip-tombstones-after"
    cc.getMillisDuration(key).requiring(_ >= Duration.Zero, key + " >= 0s")
  }

  // specific to the [[akka.cluster.DefaultDowningProvider]]
  val AutoDownUnreachableAfter: Duration = {
    val key = "auto-down-unreachable-after"
    toRootLowerCase(cc.getString(key)) match {
      case "off" => Duration.Undefined
      case _     => cc.getMillisDuration(key).requiring(_ >= Duration.Zero, key + " >= 0s, or off")
    }
  }

  /**
   * @deprecated Specific to [[akka.cluster.AutoDown]] should not be used anywhere else, instead
   *   ``Cluster.downingProvider.downRemovalMargin`` should be used as it allows the downing provider to decide removal
   *   margins
   */
  @deprecated("Use Cluster.downingProvider.downRemovalMargin", since = "2.4.5")
  val DownRemovalMargin: FiniteDuration = {
    val key = "down-removal-margin"
    toRootLowerCase(cc.getString(key)) match {
      case "off" => Duration.Zero
      case _     => cc.getMillisDuration(key).requiring(_ >= Duration.Zero, key + " >= 0s, or off")
    }
  }

  val DowningProviderClassName: String = {
    val name = cc.getString("downing-provider-class")
    if (name.nonEmpty) name
    else if (AutoDownUnreachableAfter.isFinite) classOf[AutoDowning].getName
    else classOf[NoDowning].getName
  }

  val QuarantineRemovedNodeAfter: FiniteDuration =
    cc.getMillisDuration("quarantine-removed-node-after")
      .requiring(_ > Duration.Zero, "quarantine-removed-node-after must be > 0")

  val AllowWeaklyUpMembers: Boolean = cc.getBoolean("allow-weakly-up-members")

  val SelfDataCenter: DataCenter = cc.getString("multi-data-center.self-data-center")

  val Roles: Set[String] = {
    val configuredRoles = immutableSeq(cc.getStringList("roles")).toSet.requiring(
      _.forall(!_.startsWith(DcRolePrefix)),
      s"Roles must not start with '$DcRolePrefix' as that is reserved for the cluster self-data-center setting")

    configuredRoles + s"$DcRolePrefix$SelfDataCenter"
  }

  val MinNrOfMembers: Int = {
    cc.getInt("min-nr-of-members")
  }.requiring(_ > 0, "min-nr-of-members must be > 0")
  val MinNrOfMembersOfRole: Map[String, Int] = {
    import akka.util.ccompat.JavaConverters._
    cc.getConfig("role")
      .root
      .asScala
      .collect {
        case (key, value: ConfigObject) => key -> value.toConfig.getInt("min-nr-of-members")
      }
      .toMap
  }
  val RunCoordinatedShutdownWhenDown: Boolean = cc.getBoolean("run-coordinated-shutdown-when-down")
  val JmxEnabled: Boolean = cc.getBoolean("jmx.enabled")
  val JmxMultiMbeansInSameEnabled: Boolean = cc.getBoolean("jmx.multi-mbeans-in-same-jvm")
  val UseDispatcher: String = cc.getString("use-dispatcher")
  val GossipDifferentViewProbability: Double = cc.getDouble("gossip-different-view-probability")
  val ReduceGossipDifferentViewProbability: Int = cc.getInt("reduce-gossip-different-view-probability")
  val SchedulerTickDuration: FiniteDuration = cc.getMillisDuration("scheduler.tick-duration")
  val SchedulerTicksPerWheel: Int = cc.getInt("scheduler.ticks-per-wheel")

  val ByPassConfigCompatCheck: Boolean = !cc.getBoolean("configuration-compatibility-check.enforce-on-join")
  val ConfigCompatCheckers: Set[String] = {
    import akka.util.ccompat.JavaConverters._
    cc.getConfig("configuration-compatibility-check.checkers")
      .root
      .unwrapped
      .values()
      .asScala
      .iterator
      .collect {
        case s if s.toString.trim.nonEmpty => s.toString
      }
      .toSet
  }

  val SensitiveConfigPaths = {
    import akka.util.ccompat.JavaConverters._

    val sensitiveKeys =
      cc.getConfig("configuration-compatibility-check.sensitive-config-paths")
        .root
        .unwrapped
        .values()
        .asScala
        .flatMap(_.asInstanceOf[java.util.List[String]].asScala)

    sensitiveKeys.toSet
  }

  object Debug {
    val VerboseHeartbeatLogging: Boolean = cc.getBoolean("debug.verbose-heartbeat-logging")
    val VerboseGossipLogging: Boolean = cc.getBoolean("debug.verbose-gossip-logging")
  }

}
