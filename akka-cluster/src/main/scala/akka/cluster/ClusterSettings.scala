/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.ConfigurationException
import akka.actor.Address
import akka.actor.AddressFromURIString
import akka.dispatch.Dispatchers
import akka.util.Helpers.Requiring
import scala.concurrent.duration.FiniteDuration
import akka.japi.Util.immutableSeq

class ClusterSettings(val config: Config, val systemName: String) {

  private val cc = config.getConfig("akka.cluster")

  final val FailureDetectorConfig: Config = cc.getConfig("failure-detector")
  final val FailureDetectorImplementationClass: String = FailureDetectorConfig.getString("implementation-class")
  final val HeartbeatInterval: FiniteDuration = {
    Duration(FailureDetectorConfig.getMilliseconds("heartbeat-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-interval must be > 0")
  final val HeartbeatRequestDelay: FiniteDuration = {
    Duration(FailureDetectorConfig.getMilliseconds("heartbeat-request.grace-period"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-request.grace-period must be > 0")
  final val HeartbeatExpectedResponseAfter: FiniteDuration = {
    Duration(FailureDetectorConfig.getMilliseconds("heartbeat-request.expected-response-after"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-request.expected-response-after > 0")
  final val HeartbeatRequestTimeToLive: FiniteDuration = {
    Duration(FailureDetectorConfig.getMilliseconds("heartbeat-request.time-to-live"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-request.time-to-live > 0")
  final val NumberOfEndHeartbeats: Int = {
    FailureDetectorConfig.getInt("nr-of-end-heartbeats")
  } requiring (_ > 0, "failure-detector.nr-of-end-heartbeats must be > 0")
  final val MonitoredByNrOfMembers: Int = {
    FailureDetectorConfig.getInt("monitored-by-nr-of-members")
  } requiring (_ > 0, "failure-detector.monitored-by-nr-of-members must be > 0")

  final val SeedNodes: immutable.IndexedSeq[Address] =
    immutableSeq(cc.getStringList("seed-nodes")).map { case AddressFromURIString(addr) ⇒ addr }.toVector
  final val SeedNodeTimeout: FiniteDuration = Duration(cc.getMilliseconds("seed-node-timeout"), MILLISECONDS)
  final val RetryUnsuccessfulJoinAfter: Duration = {
    val key = "retry-unsuccessful-join-after"
    cc.getString(key).toLowerCase match {
      case "off" ⇒ Duration.Undefined
      case _     ⇒ Duration(cc.getMilliseconds(key), MILLISECONDS) requiring (_ > Duration.Zero, key + " > 0s, or off")
    }
  }
  final val PeriodicTasksInitialDelay: FiniteDuration = Duration(cc.getMilliseconds("periodic-tasks-initial-delay"), MILLISECONDS)
  final val GossipInterval: FiniteDuration = Duration(cc.getMilliseconds("gossip-interval"), MILLISECONDS)
  final val LeaderActionsInterval: FiniteDuration = Duration(cc.getMilliseconds("leader-actions-interval"), MILLISECONDS)
  final val UnreachableNodesReaperInterval: FiniteDuration = Duration(cc.getMilliseconds("unreachable-nodes-reaper-interval"), MILLISECONDS)
  final val PublishStatsInterval: Duration = {
    val key = "publish-stats-interval"
    cc.getString(key).toLowerCase match {
      case "off" ⇒ Duration.Undefined
      case _     ⇒ Duration(cc.getMilliseconds(key), MILLISECONDS) requiring (_ >= Duration.Zero, key + " >= 0s, or off")
    }
  }
  final val AutoJoin: Boolean = cc.getBoolean("auto-join")
  final val AutoDown: Boolean = cc.getBoolean("auto-down")
  final val Roles: Set[String] = immutableSeq(cc.getStringList("roles")).toSet
  final val MinNrOfMembers: Int = {
    cc.getInt("min-nr-of-members")
  } requiring (_ > 0, "min-nr-of-members must be > 0")
  final val MinNrOfMembersOfRole: Map[String, Int] = {
    import scala.collection.JavaConverters._
    cc.getConfig("role").root.asScala.collect {
      case (key, value: ConfigObject) ⇒ (key -> value.toConfig.getInt("min-nr-of-members"))
    }.toMap
  }
  final val JmxEnabled: Boolean = cc.getBoolean("jmx.enabled")
  final val UseDispatcher: String = cc.getString("use-dispatcher") match {
    case "" ⇒ Dispatchers.DefaultDispatcherId
    case id ⇒ id
  }
  final val GossipDifferentViewProbability: Double = cc.getDouble("gossip-different-view-probability")
  final val SchedulerTickDuration: FiniteDuration = Duration(cc.getMilliseconds("scheduler.tick-duration"), MILLISECONDS)
  final val SchedulerTicksPerWheel: Int = cc.getInt("scheduler.ticks-per-wheel")
  final val MetricsEnabled: Boolean = cc.getBoolean("metrics.enabled")
  final val MetricsCollectorClass: String = cc.getString("metrics.collector-class")
  final val MetricsInterval: FiniteDuration = {
    Duration(cc.getMilliseconds("metrics.collect-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "metrics.collect-interval must be > 0")
  final val MetricsGossipInterval: FiniteDuration = Duration(cc.getMilliseconds("metrics.gossip-interval"), MILLISECONDS)
  final val MetricsMovingAverageHalfLife: FiniteDuration = {
    Duration(cc.getMilliseconds("metrics.moving-average-half-life"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "metrics.moving-average-half-life must be > 0")

}

