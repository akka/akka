/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.http.impl.util.SettingsCompanion
import akka.http.impl.util._
import akka.http.scaladsl.settings.{ ConnectionPoolSettings, ClientConnectionSettings }
import com.typesafe.config.Config
import scala.concurrent.duration.Duration

/** INTERNAL API */
private[akka] final case class ConnectionPoolSettingsImpl(
  val maxConnections:     Int,
  val maxRetries:         Int,
  val maxOpenRequests:    Int,
  val pipeliningLimit:    Int,
  val idleTimeout:        Duration,
  val connectionSettings: ClientConnectionSettings)
  extends ConnectionPoolSettings {

  require(maxConnections > 0, "max-connections must be > 0")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(maxOpenRequests > 0 && (maxOpenRequests & (maxOpenRequests - 1)) == 0, "max-open-requests must be a power of 2 > 0")
  require(pipeliningLimit > 0, "pipelining-limit must be > 0")
  require(idleTimeout >= Duration.Zero, "idle-timeout must be >= 0")

  override def productPrefix = "ConnectionPoolSettings"
}

object ConnectionPoolSettingsImpl extends SettingsCompanion[ConnectionPoolSettingsImpl]("akka.http.host-connection-pool") {
  def fromSubConfig(root: Config, c: Config) = {
    ConnectionPoolSettingsImpl(
      c getInt "max-connections",
      c getInt "max-retries",
      c getInt "max-open-requests",
      c getInt "pipelining-limit",
      c getPotentiallyInfiniteDuration "idle-timeout",
      ClientConnectionSettingsImpl.fromSubConfig(root, c.getConfig("client")))
  }
}
