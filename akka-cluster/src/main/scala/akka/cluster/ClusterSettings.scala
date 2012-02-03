/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.Config
import scala.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.config.ConfigurationException
import scala.collection.JavaConverters._
import akka.actor.Address
import akka.actor.AddressExtractor

class ClusterSettings(val config: Config, val systemName: String) {
  import config._
  // cluster config section
  val FailureDetectorThreshold = getInt("akka.cluster.failure-detector.threshold")
  val FailureDetectorMaxSampleSize = getInt("akka.cluster.failure-detector.max-sample-size")
  val SeedNodeConnectionTimeout = Duration(config.getMilliseconds("akka.cluster.seed-node-connection-timeout"), MILLISECONDS)
  val MaxTimeToRetryJoiningCluster = Duration(config.getMilliseconds("akka.cluster.max-time-to-retry-joining-cluster"), MILLISECONDS)
  val InitialDelayForGossip = Duration(getMilliseconds("akka.cluster.gossip.initialDelay"), MILLISECONDS)
  val GossipFrequency = Duration(getMilliseconds("akka.cluster.gossip.frequency"), MILLISECONDS)
  val SeedNodes = Set.empty[Address] ++ getStringList("akka.cluster.seed-nodes").asScala.collect {
    case AddressExtractor(addr) ⇒ addr
  }
}
