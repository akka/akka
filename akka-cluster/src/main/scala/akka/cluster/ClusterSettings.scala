/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.ConfigurationException
import scala.collection.JavaConverters._
import akka.actor.Address
import akka.actor.AddressFromURIString

class ClusterSettings(val config: Config, val systemName: String) {
  import config._
  final val FailureDetectorThreshold = getInt("akka.cluster.failure-detector.threshold")
  final val FailureDetectorMaxSampleSize = getInt("akka.cluster.failure-detector.max-sample-size")
  final val FailureDetectorImplementationClass: Option[String] = getString("akka.cluster.failure-detector.implementation-class") match {
    case ""   ⇒ None
    case fqcn ⇒ Some(fqcn)
  }
  final val NodeToJoin: Option[Address] = getString("akka.cluster.node-to-join") match {
    case ""                         ⇒ None
    case AddressFromURIString(addr) ⇒ Some(addr)
  }
  final val PeriodicTasksInitialDelay = Duration(getMilliseconds("akka.cluster.periodic-tasks-initial-delay"), MILLISECONDS)
  final val GossipInterval = Duration(getMilliseconds("akka.cluster.gossip-interval"), MILLISECONDS)
  final val HeartbeatInterval = Duration(getMilliseconds("akka.cluster.heartbeat-interval"), MILLISECONDS)
  final val LeaderActionsInterval = Duration(getMilliseconds("akka.cluster.leader-actions-interval"), MILLISECONDS)
  final val UnreachableNodesReaperInterval = Duration(getMilliseconds("akka.cluster.unreachable-nodes-reaper-interval"), MILLISECONDS)
  final val NrOfGossipDaemons = getInt("akka.cluster.nr-of-gossip-daemons")
  final val NrOfDeputyNodes = getInt("akka.cluster.nr-of-deputy-nodes")
  final val AutoDown = getBoolean("akka.cluster.auto-down")
  final val SchedulerTickDuration = Duration(getMilliseconds("akka.cluster.scheduler.tick-duration"), MILLISECONDS)
  final val SchedulerTicksPerWheel = getInt("akka.cluster.scheduler.ticks-per-wheel")
}
