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
  val FailureDetectorThreshold = getInt("akka.cluster.failure-detector.threshold")
  val FailureDetectorMaxSampleSize = getInt("akka.cluster.failure-detector.max-sample-size")
  val NodeToJoin: Option[Address] = getString("akka.cluster.node-to-join") match {
    case ""                         ⇒ None
    case AddressFromURIString(addr) ⇒ Some(addr)
  }
  val PeriodicTasksInitialDelay = Duration(getMilliseconds("akka.cluster.periodic-tasks-initial-delay"), MILLISECONDS)
  val GossipInterval = Duration(getMilliseconds("akka.cluster.gossip-interval"), MILLISECONDS)
  val HeartbeatInterval = Duration(getMilliseconds("akka.cluster.heartbeat-interval"), MILLISECONDS)
  val LeaderActionsInterval = Duration(getMilliseconds("akka.cluster.leader-actions-interval"), MILLISECONDS)
  val UnreachableNodesReaperInterval = Duration(getMilliseconds("akka.cluster.unreachable-nodes-reaper-interval"), MILLISECONDS)
  val NrOfGossipDaemons = getInt("akka.cluster.nr-of-gossip-daemons")
  val NrOfDeputyNodes = getInt("akka.cluster.nr-of-deputy-nodes")
  val AutoDown = getBoolean("akka.cluster.auto-down")
}
