/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.net.InetAddress
import akka.config.ConfigurationException
import com.eaio.uuid.UUID
import akka.actor._

import scala.collection.JavaConverters._

object RemoteExtension extends ExtensionId[RemoteExtensionSettings] with ExtensionIdProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new RemoteExtensionSettings(system.settings.config)
}

class RemoteExtensionSettings(val config: Config) extends Extension {

  import config._

  val RemoteTransport = getString("akka.remote.layer")
  val FailureDetectorThreshold = getInt("akka.remote.failure-detector.threshold")
  val FailureDetectorMaxSampleSize = getInt("akka.remote.failure-detector.max-sample-size")
  val ShouldCompressData = config.getBoolean("akka.remote.use-compression")
  val RemoteSystemDaemonAckTimeout = Duration(config.getMilliseconds("akka.remote.remote-daemon-ack-timeout"), MILLISECONDS)

  // TODO cluster config will go into akka-cluster-reference.conf when we enable that module
  val ClusterName = getString("akka.cluster.name")
  val SeedNodes = Set.empty[RemoteAddress] ++ getStringList("akka.cluster.seed-nodes").asScala.toSeq.map(RemoteAddress(_))

  // FIXME remove nodename from config - should only be passed as command line arg or read from properties file etc.
  val NodeName: String = config.getString("akka.cluster.nodename") match {
    case ""    ⇒ new UUID().toString
    case value ⇒ value
  }

  val serverSettings = new RemoteServerSettings
  val clientSettings = new RemoteClientSettings

  class RemoteClientSettings {
    val SecureCookie: Option[String] = config.getString("akka.remote.secure-cookie") match {
      case ""     ⇒ None
      case cookie ⇒ Some(cookie)
    }

    val ReconnectionTimeWindow = Duration(config.getMilliseconds("akka.remote.client.reconnection-time-window"), MILLISECONDS)
    val ReadTimeout = Duration(config.getMilliseconds("akka.remote.client.read-timeout"), MILLISECONDS)
    val ReconnectDelay = Duration(config.getMilliseconds("akka.remote.client.reconnect-delay"), MILLISECONDS)
    val MessageFrameSize = config.getBytes("akka.remote.client.message-frame-size").toInt
  }

  class RemoteServerSettings {
    import scala.collection.JavaConverters._
    val MessageFrameSize = config.getBytes("akka.remote.server.message-frame-size").toInt
    val SecureCookie: Option[String] = config.getString("akka.remote.secure-cookie") match {
      case ""     ⇒ None
      case cookie ⇒ Some(cookie)
    }
    val RequireCookie = {
      val requireCookie = config.getBoolean("akka.remote.server.require-cookie")
      if (requireCookie && SecureCookie.isEmpty) throw new ConfigurationException(
        "Configuration option 'akka.remote.server.require-cookie' is turned on but no secure cookie is defined in 'akka.remote.secure-cookie'.")
      requireCookie
    }

    val UsePassiveConnections = config.getBoolean("akka.remote.use-passive-connections")

    val UntrustedMode = config.getBoolean("akka.remote.server.untrusted-mode")
    val Hostname = config.getString("akka.remote.server.hostname") match {
      case ""    ⇒ InetAddress.getLocalHost.getHostAddress
      case value ⇒ value
    }
    val Port = config.getInt("akka.remote.server.port")
    val ConnectionTimeout = Duration(config.getMilliseconds("akka.remote.server.connection-timeout"), MILLISECONDS)

    val Backlog = config.getInt("akka.remote.server.backlog")
  }
}