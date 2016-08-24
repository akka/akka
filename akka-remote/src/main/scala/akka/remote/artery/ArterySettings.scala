/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.util.Helpers.{ ConfigOps, Requiring }
import com.typesafe.config.Config
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

  object Advanced {
    val config = getConfig("advanced")
    import config._

    val EmbeddedMediaDriver = getBoolean("embedded-media-driver")
    val AeronDirectoryName = getString("aeron-dir") requiring (dir ⇒
      EmbeddedMediaDriver || dir.nonEmpty, "aeron-dir must be defined when using external media driver")
    val TestMode: Boolean = getBoolean("test-mode")
    val IdleCpuLevel: Int = getInt("idle-cpu-level").requiring(level ⇒
      1 <= level && level <= 10, "idle-cpu-level must be between 1 and 10")
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

    val Enabled = getBoolean("enabled")
    val Debug = getBoolean("debug")

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
