/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.Address
import akka.actor.AddressFromURIString
import akka.dispatch.Dispatchers
import akka.util.Helpers.Requiring
import scala.concurrent.duration.FiniteDuration
import akka.japi.Util.immutableSeq

final class ClusterSettings(val config: Config, val systemName: String) {

  private val cc = config.getConfig("akka.cluster")

  val LogInfo: Boolean = cc.getBoolean("log-info")
  val FailureDetectorConfig: Config = cc.getConfig("failure-detector")
  val FailureDetectorImplementationClass: String = FailureDetectorConfig.getString("implementation-class")
  val HeartbeatInterval: FiniteDuration = {
    Duration(FailureDetectorConfig.getMilliseconds("heartbeat-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-interval must be > 0")
  val HeartbeatExpectedResponseAfter: FiniteDuration = {
    Duration(FailureDetectorConfig.getMilliseconds("expected-response-after"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.expected-response-after > 0")
  val MonitoredByNrOfMembers: Int = {
    FailureDetectorConfig.getInt("monitored-by-nr-of-members")
  } requiring (_ > 0, "failure-detector.monitored-by-nr-of-members must be > 0")

  val SeedNodes: immutable.IndexedSeq[Address] =
    immutableSeq(cc.getStringList("seed-nodes")).map { case AddressFromURIString(addr) ⇒ addr }.toVector
  val SeedNodeTimeout: FiniteDuration = Duration(cc.getMilliseconds("seed-node-timeout"), MILLISECONDS)
  val RetryUnsuccessfulJoinAfter: Duration = {
    val key = "retry-unsuccessful-join-after"
    cc.getString(key).toLowerCase match {
      case "off" ⇒ Duration.Undefined
      case _     ⇒ Duration(cc.getMilliseconds(key), MILLISECONDS) requiring (_ > Duration.Zero, key + " > 0s, or off")
    }
  }
  val PeriodicTasksInitialDelay: FiniteDuration = Duration(cc.getMilliseconds("periodic-tasks-initial-delay"), MILLISECONDS)
  val GossipInterval: FiniteDuration = Duration(cc.getMilliseconds("gossip-interval"), MILLISECONDS)
  val GossipTimeToLive: FiniteDuration = {
    Duration(cc.getMilliseconds("gossip-time-to-live"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "gossip-time-to-live must be > 0")
  val LeaderActionsInterval: FiniteDuration = Duration(cc.getMilliseconds("leader-actions-interval"), MILLISECONDS)
  val UnreachableNodesReaperInterval: FiniteDuration = Duration(cc.getMilliseconds("unreachable-nodes-reaper-interval"), MILLISECONDS)
  val PublishStatsInterval: Duration = {
    val key = "publish-stats-interval"
    cc.getString(key).toLowerCase match {
      case "off" ⇒ Duration.Undefined
      case _     ⇒ Duration(cc.getMilliseconds(key), MILLISECONDS) requiring (_ >= Duration.Zero, key + " >= 0s, or off")
    }
  }

  @deprecated("akka.cluster.auto-down setting is replaced by akka.cluster.auto-down-unreachable-after", "2.3")
  val AutoDown: Boolean = cc.getBoolean("auto-down")
  val AutoDownUnreachableAfter: Duration = {
    val key = "auto-down-unreachable-after"
    cc.getString(key).toLowerCase match {
      case "off" ⇒ if (AutoDown) Duration.Zero else Duration.Undefined
      case _     ⇒ Duration(cc.getMilliseconds(key), MILLISECONDS) requiring (_ >= Duration.Zero, key + " >= 0s, or off")
    }
  }

  val Roles: Set[String] = immutableSeq(cc.getStringList("roles")).toSet
  val MinNrOfMembers: Int = {
    cc.getInt("min-nr-of-members")
  } requiring (_ > 0, "min-nr-of-members must be > 0")
  val MinNrOfMembersOfRole: Map[String, Int] = {
    import scala.collection.JavaConverters._
    cc.getConfig("role").root.asScala.collect {
      case (key, value: ConfigObject) ⇒ (key -> value.toConfig.getInt("min-nr-of-members"))
    }.toMap
  }
  val JmxEnabled: Boolean = cc.getBoolean("jmx.enabled")
  val UseDispatcher: String = cc.getString("use-dispatcher") match {
    case "" ⇒ Dispatchers.DefaultDispatcherId
    case id ⇒ id
  }
  val GossipDifferentViewProbability: Double = cc.getDouble("gossip-different-view-probability")
  val ReduceGossipDifferentViewProbability: Int = cc.getInt("reduce-gossip-different-view-probability")
  val SchedulerTickDuration: FiniteDuration = Duration(cc.getMilliseconds("scheduler.tick-duration"), MILLISECONDS)
  val SchedulerTicksPerWheel: Int = cc.getInt("scheduler.ticks-per-wheel")
  val MetricsEnabled: Boolean = cc.getBoolean("metrics.enabled")
  val MetricsCollectorClass: String = cc.getString("metrics.collector-class")
  val MetricsInterval: FiniteDuration = {
    Duration(cc.getMilliseconds("metrics.collect-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "metrics.collect-interval must be > 0")
  val MetricsGossipInterval: FiniteDuration = Duration(cc.getMilliseconds("metrics.gossip-interval"), MILLISECONDS)
  val MetricsMovingAverageHalfLife: FiniteDuration = {
    Duration(cc.getMilliseconds("metrics.moving-average-half-life"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "metrics.moving-average-half-life must be > 0")

}

