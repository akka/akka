/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.Config
import akka.util.Duration
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

  // join config
  val JoinContactPoint: Option[Address] = getString("akka.cluster.join.contact-point") match {
    case ""                     ⇒ None
    case AddressExtractor(addr) ⇒ Some(addr)
  }
  val JoinTimeout = Duration(config.getMilliseconds("akka.cluster.join.timeout"), MILLISECONDS)
  val JoinMaxTimeToRetry = Duration(config.getMilliseconds("akka.cluster.join.max-time-to-retry"), MILLISECONDS)

  // gossip config
  val GossipInitialDelay = Duration(getMilliseconds("akka.cluster.gossip.initialDelay"), MILLISECONDS)
  val GossipFrequency = Duration(getMilliseconds("akka.cluster.gossip.frequency"), MILLISECONDS)
}
