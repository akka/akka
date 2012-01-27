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

  // Gossiper
  val RemoteSystemDaemonAckTimeout = Duration(getMilliseconds("akka.remote.remote-daemon-ack-timeout"), MILLISECONDS)
  val InitialDelayForGossip = Duration(getMilliseconds("akka.remote.gossip.initialDelay"), MILLISECONDS)
  val GossipFrequency = Duration(getMilliseconds("akka.remote.gossip.frequency"), MILLISECONDS)
  // TODO cluster config will go into akka-cluster/reference.conf when we enable that module
  val SeedNodes = Set.empty[Address] ++ getStringList("akka.cluster.seed-nodes").asScala.collect {
    case AddressExtractor(addr) ⇒ addr
  }

  val UntrustedMode = getBoolean("akka.remote.untrusted-mode")
}