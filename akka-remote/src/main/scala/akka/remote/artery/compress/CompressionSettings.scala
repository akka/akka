/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery.compress

import akka.actor.ActorSystem
import com.typesafe.config.Config

/** INTERNAL API */
private[akka] class CompressionSettings(_config: Config) {
  val enabled = _config.getBoolean("enabled")
  @inline private def globalEnabled = enabled

  val debug = _config.getBoolean("debug")

  object actorRefs {
    private val c = _config.getConfig("actor-refs")

    val enabled = globalEnabled && c.getBoolean("enabled")
    val max = c.getInt("max")
  }
  object manifests {
    private val c = _config.getConfig("manifests")

    val enabled = globalEnabled && c.getBoolean("enabled")
    val max = c.getInt("max")
  }
}

/** INTERNAL API */
private[akka] object CompressionSettings { // TODO make it an extension
  def apply(config: Config): CompressionSettings = new CompressionSettings(config)
  def apply(system: ActorSystem): CompressionSettings =
    new CompressionSettings(
      system.settings.config.getConfig("akka.remote.artery.advanced.compression"))
}
