/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.net.InetAddress
import akka.config.ConfigurationException
import scala.collection.JavaConverters._
import akka.actor.Address
import akka.actor.AddressExtractor

class RemoteSettings(val config: Config, val systemName: String) {

  import config._

  val RemoteTransport = getString("akka.remote.transport")
  val LogReceive = getBoolean("akka.remote.log-received-messages")
  val LogSend = getBoolean("akka.remote.log-sent-messages")

  // AccrualFailureDetector
  val FailureDetectorThreshold = getInt("akka.remote.failure-detector.threshold")
  val FailureDetectorMaxSampleSize = getInt("akka.remote.failure-detector.max-sample-size")

  // TODO cluster config will go into akka-cluster/reference.conf when we enable that module
  // cluster config section
  val UseCluster = getBoolean("akka.cluster.use-cluster")
  val SeedNodeConnectionTimeout = Duration(config.getMilliseconds("akka.cluster.seed-node-connection-timeout"), MILLISECONDS)
  val MaxTimeToRetryJoiningCluster = Duration(config.getMilliseconds("akka.cluster.max-time-to-retry-joining-cluster"), MILLISECONDS)
  val InitalDelayForGossip = Duration(getMilliseconds("akka.cluster.gossip.initialDelay"), MILLISECONDS)
  val GossipFrequency = Duration(getMilliseconds("akka.cluster.gossip.frequency"), MILLISECONDS)
  val SeedNodes = Set.empty[RemoteNettyAddress] ++ getStringList("akka.cluster.seed-nodes").asScala.collect {
    case RemoteAddressExtractor(addr) ⇒ addr.transport
  }

  val RemoteSystemDaemonAckTimeout = Duration(getMilliseconds("akka.remote.remote-daemon-ack-timeout"), MILLISECONDS)
  val UntrustedMode = getBoolean("akka.remote.untrusted-mode")
}
