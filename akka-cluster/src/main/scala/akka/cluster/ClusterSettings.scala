/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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
import akka.util.Helpers.Requiring
import scala.concurrent.duration.FiniteDuration
import akka.japi.Util.immutableSeq

class ClusterSettings(val config: Config, val systemName: String) {
  import config._

  final val FailureDetectorThreshold: Double = {
    getDouble("akka.cluster.failure-detector.threshold")
  } requiring (_ > 0.0, "failure-detector.threshold must be > 0")
  final val FailureDetectorMaxSampleSize: Int = {
    getInt("akka.cluster.failure-detector.max-sample-size")
  } requiring (_ > 0, "failure-detector.max-sample-size must be > 0")
  final val FailureDetectorImplementationClass: String = getString("akka.cluster.failure-detector.implementation-class")
  final val FailureDetectorMinStdDeviation: FiniteDuration = {
    Duration(getMilliseconds("akka.cluster.failure-detector.min-std-deviation"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.min-std-deviation must be > 0")
  final val FailureDetectorAcceptableHeartbeatPause: FiniteDuration = {
    Duration(getMilliseconds("akka.cluster.failure-detector.acceptable-heartbeat-pause"), MILLISECONDS)
  } requiring (_ >= Duration.Zero, "failure-detector.acceptable-heartbeat-pause must be >= 0")
  final val HeartbeatInterval: FiniteDuration = {
    Duration(getMilliseconds("akka.cluster.failure-detector.heartbeat-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-interval must be > 0")
  final val HeartbeatRequestDelay: FiniteDuration = {
    Duration(getMilliseconds("akka.cluster.failure-detector.heartbeat-request.grace-period"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-request.grace-period must be > 0")
  final val HeartbeatExpectedResponseAfter: FiniteDuration = {
    Duration(getMilliseconds("akka.cluster.failure-detector.heartbeat-request.expected-response-after"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-request.expected-response-after > 0")
  final val HeartbeatRequestTimeToLive: FiniteDuration = {
    Duration(getMilliseconds("akka.cluster.failure-detector.heartbeat-request.time-to-live"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "failure-detector.heartbeat-request.time-to-live > 0")
  final val NumberOfEndHeartbeats: Int = {
    getInt("akka.cluster.failure-detector.nr-of-end-heartbeats")
  } requiring (_ > 0, "failure-detector.nr-of-end-heartbeats must be > 0")
  final val MonitoredByNrOfMembers: Int = {
    getInt("akka.cluster.failure-detector.monitored-by-nr-of-members")
  } requiring (_ > 0, "failure-detector.monitored-by-nr-of-members must be > 0")

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
  final val MinNrOfMembers: Int = {
    getInt("akka.cluster.min-nr-of-members")
  } requiring (_ > 0, "min-nr-of-members must be > 0")
  final val JmxEnabled: Boolean = getBoolean("akka.cluster.jmx.enabled")
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
    Duration(getMilliseconds("akka.cluster.metrics.collect-interval"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "metrics.collect-interval must be > 0")
  final val MetricsGossipInterval: FiniteDuration = Duration(getMilliseconds("akka.cluster.metrics.gossip-interval"), MILLISECONDS)
  final val MetricsMovingAverageHalfLife: FiniteDuration = {
    Duration(getMilliseconds("akka.cluster.metrics.moving-average-half-life"), MILLISECONDS)
  } requiring (_ > Duration.Zero, "metrics.moving-average-half-life must be > 0")
}

case class CircuitBreakerSettings(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)
