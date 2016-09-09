/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.ConfigurationException
import akka.event.Logging
import akka.event.Logging.LogLevel
import akka.stream.ActorMaterializerSettings
import akka.util.Helpers.{ ConfigOps, Requiring, toRootLowerCase }
import akka.util.WildcardIndex
import akka.NotUsed
import com.typesafe.config.Config
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** INTERNAL API */
private[akka] final class ArterySettings private (config: Config) {
  import config._
  import ArterySettings._

  val Enabled: Boolean = getBoolean("enabled")

  object Canonical {
    val config = getConfig("canonical")
    import config._

    val Port: Int = getInt("port").requiring(port ⇒
      0 to 65535 contains port, "canonical.port must be 0 through 65535")
    val Hostname: String = getHostname("hostname", config)
  }

  object Bind {
    val config = getConfig("bind")
    import config._

    val Port: Int = getString("port") match {
      case ""    ⇒ Canonical.Port
      case other ⇒ getInt("port").requiring(port ⇒ 0 to 65535 contains port, "bind.port must be 0 through 65535")
    }
    val Hostname: String = getHostname("hostname", config) match {
      case ""    ⇒ Canonical.Hostname
      case other ⇒ other
    }
  }

  val LargeMessageDestinations =
    config.getStringList("large-message-destinations").asScala.foldLeft(WildcardIndex[NotUsed]()) { (tree, entry) ⇒
      val segments = entry.split('/').tail
      tree.insert(segments, NotUsed)
    }
  val LifecycleEventsLogLevel: LogLevel = Logging.levelFor(toRootLowerCase(getString("log-lifecycle-events"))) match {
    case Some(level) ⇒ level
    case None        ⇒ throw new ConfigurationException("Logging level must be one of (off, debug, info, warning, error)")
  }
  val Dispatcher = getString("use-dispatcher")

  object Advanced {
    val config = getConfig("advanced")
    import config._

    val TestMode: Boolean = getBoolean("test-mode")
    val MaterializerSettings = ActorMaterializerSettings(config.getConfig("materializer"))
    val EmbeddedMediaDriver = getBoolean("embedded-media-driver")
    val AeronDirectoryName = getString("aeron-dir") requiring (dir ⇒
      EmbeddedMediaDriver || dir.nonEmpty, "aeron-dir must be defined when using external media driver")
    val DeleteAeronDirectory = getBoolean("delete-aeron-dir")
    val IdleCpuLevel: Int = getInt("idle-cpu-level").requiring(level ⇒
      1 <= level && level <= 10, "idle-cpu-level must be between 1 and 10")
    val OutboundLanes = getInt("outbound-lanes").requiring(n ⇒
      n > 0, "outbound-lanes must be greater than zero")
    val InboundLanes = getInt("inbound-lanes").requiring(n ⇒
      n > 0, "inbound-lanes must be greater than zero")
    val SysMsgBufferSize: Int = getInt("system-message-buffer-size").requiring(
      _ > 0, "system-message-buffer-size must be more than zero")
    val OutboundMessageQueueSize: Int = getInt("outbound-message-queue-size").requiring(
      _ > 0, "outbound-message-queue-size must be more than zero")
    val OutboundControlQueueSize: Int = getInt("outbound-control-queue-size").requiring(
      _ > 0, "outbound-control-queue-size must be more than zero")
    val OutboundLargeMessageQueueSize: Int = getInt("outbound-large-message-queue-size").requiring(
      _ > 0, "outbound-large-message-queue-size must be more than zero")
    val SystemMessageResendInterval = config.getMillisDuration("system-message-resend-interval").requiring(interval ⇒
      interval > Duration.Zero, "system-message-resend-interval must be more than zero")
    val HandshakeTimeout = config.getMillisDuration("handshake-timeout").requiring(interval ⇒
      interval > Duration.Zero, "handshake-timeout must be more than zero")
    val HandshakeRetryInterval = config.getMillisDuration("handshake-retry-interval").requiring(interval ⇒
      interval > Duration.Zero, "handshake-retry-interval must be more than zero")
    val InjectHandshakeInterval = config.getMillisDuration("inject-handshake-interval").requiring(interval ⇒
      interval > Duration.Zero, "inject-handshake-interval must be more than zero")
    val GiveUpSendAfter = config.getMillisDuration("give-up-send-after").requiring(interval ⇒
      interval > Duration.Zero, "give-up-send-after must be more than zero")
    val ShutdownFlushTimeout = config.getMillisDuration("shutdown-flush-timeout").requiring(interval ⇒
      interval > Duration.Zero, "shutdown-flush-timeout must be more than zero")
    val InboundRestartTimeout = config.getMillisDuration("inbound-restart-timeout").requiring(interval ⇒
      interval > Duration.Zero, "inbound-restart-timeout must be more than zero")
    val InboundMaxRestarts = getInt("inbound-max-restarts")
    val OutboundRestartTimeout = config.getMillisDuration("outbound-restart-timeout").requiring(interval ⇒
      interval > Duration.Zero, "outbound-restart-timeout must be more than zero")
    val OutboundMaxRestarts = getInt("outbound-max-restarts")
    val ClientLivenessTimeout = config.getMillisDuration("client-liveness-timeout").requiring(interval ⇒
      interval > Duration.Zero, "client-liveness-timeout must be more than zero")
    val ImageLivenessTimeoutNs = config.getMillisDuration("image-liveness-timeout").requiring(interval ⇒
      interval > Duration.Zero, "image-liveness-timeout must be more than zero")
    val DriverTimeout = config.getMillisDuration("driver-timeout").requiring(interval ⇒
      interval > Duration.Zero, "driver-timeout must be more than zero")
    val FlightRecorderEnabled: Boolean = getBoolean("flight-recorder.enabled")
    val Compression = new Compression(getConfig("compression"))

    final val MaximumFrameSize = 1024 * 1024
    final val MaximumPooledBuffers = 128
    final val MaximumLargeFrameSize = MaximumFrameSize * 5
    final val InboundBroadcastHubBufferSize = MaximumPooledBuffers / 2
  }
}

/** INTERNAL API */
private[akka] object ArterySettings {
  def apply(config: Config) = new ArterySettings(config)

  /** INTERNAL API */
  private[akka] final class Compression private[ArterySettings] (config: Config) {
    import config._

    final val Enabled = true

    object ActorRefs {
      val config = getConfig("actor-refs")
      import config._

      val AdvertisementInterval = config.getMillisDuration("advertisement-interval")
      val Max = getInt("max")
    }
    object Manifests {
      val config = getConfig("manifests")
      import config._

      val AdvertisementInterval = config.getMillisDuration("advertisement-interval")
      val Max = getInt("max")
    }
  }
  object Compression {
    // Compile time constants
    final val Debug = false // unlocks additional very verbose debug logging of compression events (on DEBUG log level)
  }

  def getHostname(key: String, config: Config) = config.getString(key) match {
    case "<getHostAddress>" ⇒ InetAddress.getLocalHost.getHostAddress
    case "<getHostName>"    ⇒ InetAddress.getLocalHost.getHostName
    case other              ⇒ other
  }
}
