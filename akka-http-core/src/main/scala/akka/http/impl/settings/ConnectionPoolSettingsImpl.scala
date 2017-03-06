/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.{ SettingsCompanion, _ }
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/** INTERNAL API */
@InternalApi
private[akka] final case class ConnectionPoolSettingsImpl(
  maxConnections:     Int,
  minConnections:     Int,
  maxRetries:         Int,
  maxOpenRequests:    Int,
  pipeliningLimit:    Int,
  idleTimeout:        Duration,
  connectionSettings: ClientConnectionSettings,
  transport:          ClientTransport)
  extends ConnectionPoolSettings {

  def this(
    maxConnections:     Int,
    minConnections:     Int,
    maxRetries:         Int,
    maxOpenRequests:    Int,
    pipeliningLimit:    Int,
    idleTimeout:        Duration,
    connectionSettings: ClientConnectionSettings) =
    this(maxConnections, minConnections, maxRetries, maxOpenRequests, pipeliningLimit, idleTimeout, connectionSettings,
      ClientTransport.TCP(None, connectionSettings))

  require(maxConnections > 0, "max-connections must be > 0")
  require(minConnections >= 0, "min-connections must be >= 0")
  require(minConnections <= maxConnections, "min-connections must be <= max-connections")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(maxOpenRequests > 0 && (maxOpenRequests & (maxOpenRequests - 1)) == 0, "max-open-requests must be a power of 2 > 0")
  require(pipeliningLimit > 0, "pipelining-limit must be > 0")
  require(idleTimeout >= Duration.Zero, "idle-timeout must be >= 0")

  override def productPrefix = "ConnectionPoolSettings"
}

object ConnectionPoolSettingsImpl extends SettingsCompanion[ConnectionPoolSettingsImpl]("akka.http.host-connection-pool") {
  def fromSubConfig(root: Config, c: Config) = {
    new ConnectionPoolSettingsImpl(
      c getInt "max-connections",
      c getInt "min-connections",
      c getInt "max-retries",
      c getInt "max-open-requests",
      c getInt "pipelining-limit",
      c getPotentiallyInfiniteDuration "idle-timeout",
      ClientConnectionSettingsImpl.fromSubConfig(root, c.getConfig("client"))
    )
  }
}
