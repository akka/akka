/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import scala.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.net.InetAddress
import akka.config.ConfigurationException
import com.eaio.uuid.UUID
import scala.collection.JavaConverters._

class RemoteSettings(val config: Config, val systemName: String) {

  import config._

  final val RemoteTransport = getString("akka.remote.transport")
  final val FailureDetectorThreshold = getInt("akka.remote.failure-detector.threshold")
  final val FailureDetectorMaxSampleSize = getInt("akka.remote.failure-detector.max-sample-size")
  final val ShouldCompressData = getBoolean("akka.remote.use-compression")
  final val RemoteSystemDaemonAckTimeout = Duration(getMilliseconds("akka.remote.remote-daemon-ack-timeout"), MILLISECONDS)
  final val InitalDelayForGossip = Duration(getMilliseconds("akka.remote.gossip.initialDelay"), MILLISECONDS)
  final val GossipFrequency = Duration(getMilliseconds("akka.remote.gossip.frequency"), MILLISECONDS)
  final val BackoffTimeout = Duration(getMilliseconds("akka.remote.backoff-timeout"), MILLISECONDS)
  final val LogReceivedMessages = getBoolean("akka.remote.log-received-messages")
  final val LogSentMessages = getBoolean("akka.remote.log-sent-messages")

  // TODO cluster config will go into akka-cluster/reference.conf when we enable that module
  final val SeedNodes = Set.empty[RemoteNettyAddress] ++ getStringList("akka.cluster.seed-nodes").asScala.collect {
    case RemoteAddressExtractor(addr) ⇒ addr.transport
  }

  final val serverSettings = new RemoteServerSettings
  final val clientSettings = new RemoteClientSettings

  class RemoteClientSettings {
    val SecureCookie: Option[String] = getString("akka.remote.secure-cookie") match {
      case ""     ⇒ None
      case cookie ⇒ Some(cookie)
    }

    final val ConnectionTimeout = Duration(getMilliseconds("akka.remote.client.connection-timeout"), MILLISECONDS)
    final val ReconnectionTimeWindow = Duration(getMilliseconds("akka.remote.client.reconnection-time-window"), MILLISECONDS)
    final val ReadTimeout = Duration(getMilliseconds("akka.remote.client.read-timeout"), MILLISECONDS)
    final val ReconnectDelay = Duration(getMilliseconds("akka.remote.client.reconnect-delay"), MILLISECONDS)
    final val MessageFrameSize = getBytes("akka.remote.client.message-frame-size").toInt
  }

  class RemoteServerSettings {
    import scala.collection.JavaConverters._
    final val MessageFrameSize = getBytes("akka.remote.server.message-frame-size").toInt
    final val SecureCookie: Option[String] = getString("akka.remote.secure-cookie") match {
      case ""     ⇒ None
      case cookie ⇒ Some(cookie)
    }
    final val RequireCookie = {
      val requireCookie = getBoolean("akka.remote.server.require-cookie")
      if (requireCookie && SecureCookie.isEmpty) throw new ConfigurationException(
        "Configuration option 'akka.remote.server.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.secure-cookie'.")
      requireCookie
    }

    final val UsePassiveConnections = getBoolean("akka.remote.use-passive-connections")

    final val UntrustedMode = getBoolean("akka.remote.server.untrusted-mode")
    final val Hostname = getString("akka.remote.server.hostname") match {
      case ""    ⇒ InetAddress.getLocalHost.getHostAddress
      case value ⇒ value
    }
    final val Port = getInt("akka.remote.server.port") match {
      case 0 ⇒ try {
        val s = new java.net.ServerSocket(0)
        try s.getLocalPort finally s.close()
      } catch { case e ⇒ throw new ConfigurationException("Unable to obtain random port", e) }
      case other ⇒ other
    }

    final val Backlog = getInt("akka.remote.server.backlog")

    final val ExecutionPoolKeepAlive = Duration(getMilliseconds("akka.remote.server.execution-pool-keepalive"), MILLISECONDS)

    final val ExecutionPoolSize = getInt("akka.remote.server.execution-pool-size") match {
      case sz if sz < 1 ⇒ throw new IllegalArgumentException("akka.remote.server.execution-pool-size is less than 1")
      case sz           ⇒ sz
    }

    final val MaxChannelMemorySize = getBytes("akka.remote.server.max-channel-memory-size") match {
      case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.server.max-channel-memory-size is less than 0 bytes")
      case sz           ⇒ sz
    }

    final val MaxTotalMemorySize = getBytes("akka.remote.server.max-total-memory-size") match {
      case sz if sz < 0 ⇒ throw new IllegalArgumentException("akka.remote.server.max-total-memory-size is less than 0 bytes")
      case sz           ⇒ sz
    }

    // TODO handle the system name right and move this to config file syntax
    final val URI = "akka://sys@" + Hostname + ":" + Port
  }
}