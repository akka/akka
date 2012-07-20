/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.Config
import scala.concurrent.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.ConfigurationException
import scala.collection.JavaConverters._
import akka.actor.Address
import akka.actor.AddressFromURIString
import akka.dispatch.Dispatchers

class ClusterSettings(val config: Config, val systemName: String) {
  import config._

  final val FailureDetectorThreshold = getDouble("akka.cluster.failure-detector.threshold")
  final val FailureDetectorMaxSampleSize = getInt("akka.cluster.failure-detector.max-sample-size")
  final val FailureDetectorImplementationClass = getString("akka.cluster.failure-detector.implementation-class")
  final val FailureDetectorMinStdDeviation: Duration =
    Duration(getMilliseconds("akka.cluster.failure-detector.min-std-deviation"), MILLISECONDS)
  final val FailureDetectorAcceptableHeartbeatPause: Duration =
    Duration(getMilliseconds("akka.cluster.failure-detector.acceptable-heartbeat-pause"), MILLISECONDS)

  final val SeedNodes: IndexedSeq[Address] = getStringList("akka.cluster.seed-nodes").asScala.map {
    case AddressFromURIString(addr) ⇒ addr
  }.toIndexedSeq
  final val SeedNodeTimeout: Duration = Duration(getMilliseconds("akka.cluster.seed-node-timeout"), MILLISECONDS)
  final val PeriodicTasksInitialDelay: Duration = Duration(getMilliseconds("akka.cluster.periodic-tasks-initial-delay"), MILLISECONDS)
  final val GossipInterval: Duration = Duration(getMilliseconds("akka.cluster.gossip-interval"), MILLISECONDS)
  final val HeartbeatInterval: Duration = Duration(getMilliseconds("akka.cluster.heartbeat-interval"), MILLISECONDS)
  final val LeaderActionsInterval: Duration = Duration(getMilliseconds("akka.cluster.leader-actions-interval"), MILLISECONDS)
  final val UnreachableNodesReaperInterval: Duration = Duration(getMilliseconds("akka.cluster.unreachable-nodes-reaper-interval"), MILLISECONDS)
  final val PublishStateInterval: Duration = Duration(getMilliseconds("akka.cluster.publish-state-interval"), MILLISECONDS)
  final val AutoJoin: Boolean = getBoolean("akka.cluster.auto-join")
  final val AutoDown: Boolean = getBoolean("akka.cluster.auto-down")
  final val JoinTimeout: Duration = Duration(getMilliseconds("akka.cluster.join-timeout"), MILLISECONDS)
  final val UseDispatcher: String = getString("akka.cluster.use-dispatcher") match {
    case "" ⇒ Dispatchers.DefaultDispatcherId
    case id ⇒ id
  }
  final val GossipDifferentViewProbability: Double = getDouble("akka.cluster.gossip-different-view-probability")
  final val MaxGossipMergeRate: Double = getDouble("akka.cluster.max-gossip-merge-rate")
  final val SchedulerTickDuration: Duration = Duration(getMilliseconds("akka.cluster.scheduler.tick-duration"), MILLISECONDS)
  final val SchedulerTicksPerWheel: Int = getInt("akka.cluster.scheduler.ticks-per-wheel")
  final val SendCircuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(
    maxFailures = getInt("akka.cluster.send-circuit-breaker.max-failures"),
    callTimeout = Duration(getMilliseconds("akka.cluster.send-circuit-breaker.call-timeout"), MILLISECONDS),
    resetTimeout = Duration(getMilliseconds("akka.cluster.send-circuit-breaker.reset-timeout"), MILLISECONDS))
}

case class CircuitBreakerSettings(maxFailures: Int, callTimeout: Duration, resetTimeout: Duration)
