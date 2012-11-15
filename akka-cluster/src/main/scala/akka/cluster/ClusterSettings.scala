/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable
import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.ConfigurationException
import akka.actor.Address
import akka.actor.AddressFromURIString
import akka.dispatch.Dispatchers
import scala.concurrent.duration.FiniteDuration
import akka.japi.Util.immutableSeq

class ClusterSettings(val config: Config, val systemName: String) {
  import config._

  final val FailureDetectorThreshold: Double = {
    val x = getDouble("akka.cluster.failure-detector.threshold")
    require(x > 0.0, "failure-detector.threshold must be > 0")
    x
  }
  final val FailureDetectorMaxSampleSize: Int = {
    val n = getInt("akka.cluster.failure-detector.max-sample-size")
    require(n > 0, "failure-detector.max-sample-size must be > 0"); n
  }
  final val FailureDetectorImplementationClass: String = getString("akka.cluster.failure-detector.implementation-class")
  final val FailureDetectorMinStdDeviation: FiniteDuration = {
    val d = Duration(getMilliseconds("akka.cluster.failure-detector.min-std-deviation"), MILLISECONDS)
    require(d > Duration.Zero, "failure-detector.min-std-deviation must be > 0"); d
  }
  final val FailureDetectorAcceptableHeartbeatPause: FiniteDuration = {
    val d = Duration(getMilliseconds("akka.cluster.failure-detector.acceptable-heartbeat-pause"), MILLISECONDS)
    require(d >= Duration.Zero, "failure-detector.acceptable-heartbeat-pause must be >= 0"); d
  }
  final val HeartbeatInterval: FiniteDuration = {
    val d = Duration(getMilliseconds("akka.cluster.failure-detector.heartbeat-interval"), MILLISECONDS)
    require(d > Duration.Zero, "failure-detector.heartbeat-interval must be > 0"); d
  }
  final val HeartbeatConsistentHashingVirtualNodesFactor = 10 // no need for configuration
  final val NumberOfEndHeartbeats: Int = (FailureDetectorAcceptableHeartbeatPause / HeartbeatInterval + 1).toInt
  final val MonitoredByNrOfMembers: Int = {
    val n = getInt("akka.cluster.failure-detector.monitored-by-nr-of-members")
    require(n > 0, "failure-detector.monitored-by-nr-of-members must be > 0"); n
  }

  final val SeedNodes: immutable.IndexedSeq[Address] =
    immutableSeq(getStringList("akka.cluster.seed-nodes")).map { case AddressFromURIString(addr) ⇒ addr }.toVector
  final val SeedNodeTimeout: FiniteDuration = Duration(getMilliseconds("akka.cluster.seed-node-timeout"), MILLISECONDS)
  final val PeriodicTasksInitialDelay: FiniteDuration = Duration(getMilliseconds("akka.cluster.periodic-tasks-initial-delay"), MILLISECONDS)
  final val GossipInterval: FiniteDuration = Duration(getMilliseconds("akka.cluster.gossip-interval"), MILLISECONDS)
  final val LeaderActionsInterval: FiniteDuration = Duration(getMilliseconds("akka.cluster.leader-actions-interval"), MILLISECONDS)
  final val UnreachableNodesReaperInterval: FiniteDuration = Duration(getMilliseconds("akka.cluster.unreachable-nodes-reaper-interval"), MILLISECONDS)
  final val PublishStatsInterval: FiniteDuration = Duration(getMilliseconds("akka.cluster.publish-stats-interval"), MILLISECONDS)
  final val AutoJoin: Boolean = getBoolean("akka.cluster.auto-join")
  final val AutoDown: Boolean = getBoolean("akka.cluster.auto-down")
  final val JmxEnabled: Boolean = getBoolean("akka.cluster.jmx.enabled")
  final val JoinTimeout: FiniteDuration = Duration(getMilliseconds("akka.cluster.join-timeout"), MILLISECONDS)
  final val UseDispatcher: String = getString("akka.cluster.use-dispatcher") match {
    case "" ⇒ Dispatchers.DefaultDispatcherId
    case id ⇒ id
  }
  final val GossipDifferentViewProbability: Double = getDouble("akka.cluster.gossip-different-view-probability")
  final val MaxGossipMergeRate: Double = getDouble("akka.cluster.max-gossip-merge-rate")
  final val SchedulerTickDuration: FiniteDuration = Duration(getMilliseconds("akka.cluster.scheduler.tick-duration"), MILLISECONDS)
  final val SchedulerTicksPerWheel: Int = getInt("akka.cluster.scheduler.ticks-per-wheel")
  final val SendCircuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(
    maxFailures = getInt("akka.cluster.send-circuit-breaker.max-failures"),
    callTimeout = Duration(getMilliseconds("akka.cluster.send-circuit-breaker.call-timeout"), MILLISECONDS),
    resetTimeout = Duration(getMilliseconds("akka.cluster.send-circuit-breaker.reset-timeout"), MILLISECONDS))
  final val MetricsEnabled: Boolean = getBoolean("akka.cluster.metrics.enabled")
  final val MetricsCollectorClass: String = getString("akka.cluster.metrics.collector-class")
  final val MetricsInterval: FiniteDuration = {
    val d = Duration(getMilliseconds("akka.cluster.metrics.collect-interval"), MILLISECONDS)
    require(d > Duration.Zero, "metrics.collect-interval must be > 0"); d
  }
  final val MetricsGossipInterval: FiniteDuration = Duration(getMilliseconds("akka.cluster.metrics.gossip-interval"), MILLISECONDS)
  final val MetricsMovingAverageHalfLife: FiniteDuration = {
    val d = Duration(getMilliseconds("akka.cluster.metrics.moving-average-half-life"), MILLISECONDS)
    require(d > Duration.Zero, "metrics.moving-average-half-life must be > 0"); d
  }
}

case class CircuitBreakerSettings(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)
