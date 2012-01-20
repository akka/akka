/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.net.InetAddress
import akka.config.ConfigurationException
import com.eaio.uuid.UUID
import scala.collection.JavaConverters._

class RemoteSettings(val config: Config, val systemName: String) {

  import config._

  val RemoteTransport = getString("akka.remote.transport")
  val FailureDetectorThreshold = getInt("akka.remote.failure-detector.threshold")
  val FailureDetectorMaxSampleSize = getInt("akka.remote.failure-detector.max-sample-size")
  val ShouldCompressData = getBoolean("akka.remote.use-compression")
  val RemoteSystemDaemonAckTimeout = Duration(getMilliseconds("akka.remote.remote-daemon-ack-timeout"), MILLISECONDS)
  val InitalDelayForGossip = Duration(getMilliseconds("akka.remote.gossip.initialDelay"), MILLISECONDS)
  val GossipFrequency = Duration(getMilliseconds("akka.remote.gossip.frequency"), MILLISECONDS)
  val BackoffTimeout = Duration(getMilliseconds("akka.remote.backoff-timeout"), MILLISECONDS)

  // TODO cluster config will go into akka-cluster/reference.conf when we enable that module
  val SeedNodes = Set.empty[RemoteNettyAddress] ++ getStringList("akka.cluster.seed-nodes").asScala.collect {
    case RemoteAddressExtractor(addr) ⇒ addr.transport
  }

  val serverSettings = new RemoteServerSettings
  val clientSettings = new RemoteClientSettings

  class RemoteClientSettings {
    val SecureCookie: Option[String] = getString("akka.remote.secure-cookie") match {
      case ""     ⇒ None
      case cookie ⇒ Some(cookie)
    }

    val ReconnectionTimeWindow = Duration(getMilliseconds("akka.remote.client.reconnection-time-window"), MILLISECONDS)
    val ReadTimeout = Duration(getMilliseconds("akka.remote.client.read-timeout"), MILLISECONDS)
    val ReconnectDelay = Duration(getMilliseconds("akka.remote.client.reconnect-delay"), MILLISECONDS)
    val MessageFrameSize = getBytes("akka.remote.client.message-frame-size").toInt
  }

  class RemoteServerSettings {
    import scala.collection.JavaConverters._
    val MessageFrameSize = getBytes("akka.remote.server.message-frame-size").toInt
    val SecureCookie: Option[String] = getString("akka.remote.secure-cookie") match {
      case ""     ⇒ None
      case cookie ⇒ Some(cookie)
    }
    val RequireCookie = {
      val requireCookie = getBoolean("akka.remote.server.require-cookie")
      if (requireCookie && SecureCookie.isEmpty) throw new ConfigurationException(
        "Configuration option 'akka.remote.server.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.secure-cookie'.")
      requireCookie
    }

    val UsePassiveConnections = getBoolean("akka.remote.use-passive-connections")

    val UntrustedMode = getBoolean("akka.remote.server.untrusted-mode")
    val Hostname = getString("akka.remote.server.hostname") match {
      case ""    ⇒ InetAddress.getLocalHost.getHostAddress
      case value ⇒ value
    }
    val Port = getInt("akka.remote.server.port")
    val ConnectionTimeout = Duration(getMilliseconds("akka.remote.server.connection-timeout"), MILLISECONDS)

    val Backlog = getInt("akka.remote.server.backlog")

    val ExecutionPoolKeepAlive = Duration(getMilliseconds("akka.remote.server.execution-pool-keepalive"), MILLISECONDS)

    val ExecutionPoolSize = getInt("akka.remote.server.execution-pool-size") match {
      case sz if sz < 1 ⇒ throw new IllegalArgumentException("akka.remote.server.execution-pool-size is less than 1")
      case sz           ⇒ sz
    }

    val MaxChannelMemorySize = getBytes("akka.remote.server.max-channel-memory-size") match {
      case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.server.max-channel-memory-size is less than 0 bytes")
      case sz           ⇒ sz
    }

    val MaxTotalMemorySize = getBytes("akka.remote.server.max-total-memory-size") match {
      case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.server.max-total-memory-size is less than 0 bytes")
      case sz           ⇒ sz
    }

    // TODO handle the system name right and move this to config file syntax
    val URI = "akka://sys@" + Hostname + ":" + Port
  }
}