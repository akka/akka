/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import scala.collection.immutable
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject

import scala.concurrent.duration.Duration
import akka.actor.Address
import akka.actor.AddressFromURIString
import akka.dispatch.Dispatchers
import akka.util.Helpers.{ Requiring, ConfigOps, toRootLowerCase }

import scala.concurrent.duration.FiniteDuration
import akka.japi.Util.immutableSeq

final class ClusterSettings(val config: Config, val systemName: String) {

  private val cc = config.getConfig("akka.cluster")

  val LogInfo: Boolean = cc.getBoolean("log-info")
  val FailureDetectorConfig: Config = cc.getConfig("failure-detector")
  val FailureDetectorImplementationClass: String = FailureDetectorConfig.getString("implementation-class")
  val HeartbeatInterval: FiniteDuration = {
    FailureDetectorConfig.getMillisDuration("heartbeat-interval")
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-interval must be > 0")
  val HeartbeatExpectedResponseAfter: FiniteDuration = {
    FailureDetectorConfig.getMillisDuration("expected-response-after")
  } requiring (_ > Duration.Zero, "failure-detector.expected-response-after > 0")
  val MonitoredByNrOfMembers: Int = {
    FailureDetectorConfig.getInt("monitored-by-nr-of-members")
  } requiring (_ > 0, "failure-detector.monitored-by-nr-of-members must be > 0")

  val SeedNodes: immutable.IndexedSeq[Address] =
    immutableSeq(cc.getStringList("seed-nodes")).map { case AddressFromURIString(addr) ⇒ addr }.toVector
  val SeedNodeTimeout: FiniteDuration = cc.getMillisDuration("seed-node-timeout")
  val RetryUnsuccessfulJoinAfter: Duration = {
    val key = "retry-unsuccessful-join-after"
    toRootLowerCase(cc.getString(key)) match {
      case "off" ⇒ Duration.Undefined
      case _     ⇒ cc.getMillisDuration(key) requiring (_ > Duration.Zero, key + " > 0s, or off")
    }
  }
  val PeriodicTasksInitialDelay: FiniteDuration = cc.getMillisDuration("periodic-tasks-initial-delay")
  val GossipInterval: FiniteDuration = cc.getMillisDuration("gossip-interval")
  val GossipTimeToLive: FiniteDuration = {
    cc.getMillisDuration("gossip-time-to-live")
  } requiring (_ > Duration.Zero, "gossip-time-to-live must be > 0")
  val LeaderActionsInterval: FiniteDuration = cc.getMillisDuration("leader-actions-interval")
  val UnreachableNodesReaperInterval: FiniteDuration = cc.getMillisDuration("unreachable-nodes-reaper-interval")
  val PublishStatsInterval: Duration = {
    val key = "publish-stats-interval"
    toRootLowerCase(cc.getString(key)) match {
      case "off" ⇒ Duration.Undefined
      case _     ⇒ cc.getMillisDuration(key) requiring (_ >= Duration.Zero, key + " >= 0s, or off")
    }
  }

  // specific to the [[akka.cluster.DefaultDowningProvider]]
  val AutoDownUnreachableAfter: Duration = {
    val key = "auto-down-unreachable-after"
    toRootLowerCase(cc.getString(key)) match {
      case "off" ⇒ Duration.Undefined
      case _     ⇒ cc.getMillisDuration(key) requiring (_ >= Duration.Zero, key + " >= 0s, or off")
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
      case "off" ⇒ Duration.Zero
      case _     ⇒ cc.getMillisDuration(key) requiring (_ >= Duration.Zero, key + " >= 0s, or off")
    }
  }

  val DowningProviderClassName: String = {
    val name = cc.getString("downing-provider-class")
    if (name.nonEmpty) name
    else if (AutoDownUnreachableAfter.isFinite()) classOf[AutoDowning].getName
    else classOf[NoDowning].getName
  }

  val QuarantineRemovedNodeAfter: FiniteDuration =
    cc.getMillisDuration("quarantine-removed-node-after") requiring (_ > Duration.Zero, "quarantine-removed-node-after must be > 0")

  val AllowWeaklyUpMembers = cc.getBoolean("allow-weakly-up-members")

  val Roles: Set[String] = immutableSeq(cc.getStringList("roles")).toSet
  val MinNrOfMembers: Int = {
    cc.getInt("min-nr-of-members")
  } requiring (_ > 0, "min-nr-of-members must be > 0")
  val MinNrOfMembersOfRole: Map[String, Int] = {
    import scala.collection.JavaConverters._
    cc.getConfig("role").root.asScala.collect {
      case (key, value: ConfigObject) ⇒ key → value.toConfig.getInt("min-nr-of-members")
    }.toMap
  }
  val JmxEnabled: Boolean = cc.getBoolean("jmx.enabled")
  val UseDispatcher: String = cc.getString("use-dispatcher") match {
    case "" ⇒ Dispatchers.DefaultDispatcherId
    case id ⇒ id
  }
  val GossipDifferentViewProbability: Double = cc.getDouble("gossip-different-view-probability")
  val ReduceGossipDifferentViewProbability: Int = cc.getInt("reduce-gossip-different-view-probability")
  val SchedulerTickDuration: FiniteDuration = cc.getMillisDuration("scheduler.tick-duration")
  val SchedulerTicksPerWheel: Int = cc.getInt("scheduler.ticks-per-wheel")
  val MetricsEnabled: Boolean = cc.getBoolean("metrics.enabled")
  val MetricsCollectorClass: String = cc.getString("metrics.collector-class")
  val MetricsInterval: FiniteDuration = {
    cc.getMillisDuration("metrics.collect-interval")
  } requiring (_ > Duration.Zero, "metrics.collect-interval must be > 0")
  val MetricsGossipInterval: FiniteDuration = cc.getMillisDuration("metrics.gossip-interval")
  val MetricsMovingAverageHalfLife: FiniteDuration = {
    cc.getMillisDuration("metrics.moving-average-half-life")
  } requiring (_ > Duration.Zero, "metrics.moving-average-half-life must be > 0")

  object Debug {
    val VerboseHeartbeatLogging = cc.getBoolean("debug.verbose-heartbeat-logging")
  }

}

