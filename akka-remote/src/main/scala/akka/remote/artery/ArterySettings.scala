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
  val Port: Int = getInt("port")
  val Hostname: String = getString("hostname") match {
    case "" | "<getHostAddress>" ⇒ InetAddress.getLocalHost.getHostAddress
    case "<getHostName>"         ⇒ InetAddress.getLocalHost.getHostName
    case other                   ⇒ other
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
    val SysMsgBufferSize: Int = getInt("system-message-buffer-size").requiring(
      _ > 0, "system-message-buffer-size must be more than zero")
    val SystemMessageResendInterval = config.getMillisDuration("system-message-resend-interval").requiring(interval ⇒
      interval > 0.seconds, "system-message-resend-interval must be more than zero")
    val HandshakeRetryInterval = config.getMillisDuration("handshake-retry-interval").requiring(interval ⇒
      interval > 0.seconds, "handshake-retry-interval must be more than zero")
    val InjectHandshakeInterval = config.getMillisDuration("inject-handshake-interval").requiring(interval ⇒
      interval > 0.seconds, "inject-handshake-interval must be more than zero")
    val GiveUpSendAfter = config.getMillisDuration("give-up-send-after")
    val ShutdownFlushTimeout = config.getMillisDuration("shutdown-flush-timeout")
    val InboundRestartTimeout = config.getMillisDuration("inbound-restart-timeout")
    val InboundMaxRestarts = getInt("inbound-max-restarts")
    val OutboundRestartTimeout = config.getMillisDuration("outbound-restart-timeout")
    val OutboundMaxRestarts = getInt("outbound-max-restarts")
    val ClientLivenessTimeout = config.getMillisDuration("client-liveness-timeout")
    val ImageLivenessTimeoutNs = config.getMillisDuration("image-liveness-timeout")
    val DriverTimeout = config.getMillisDuration("driver-timeout")
    val FlightRecorderEnabled: Boolean = getBoolean("flight-recorder.enabled")
    val Compression = new Compression(getConfig("compression"))
  }
}

/** INTERNAL API */
private[akka] object ArterySettings {
  def apply(config: Config) = new ArterySettings(config)

  /** INTERNAL API */
  private[akka] final class Compression private[ArterySettings] (config: Config) {
    import config._

    // Compile time constants
    final val Enabled = true
    final val Debug = false // unlocks additional very verbose debug logging of compression events (on DEBUG log level)

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
}
