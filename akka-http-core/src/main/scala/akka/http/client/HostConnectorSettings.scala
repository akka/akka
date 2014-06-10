/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import akka.http.util._

final case class HostConnectorSettings(
  maxConnections: Int,
  maxRetries: Int,
  maxRedirects: Int,
  pipelining: Boolean,
  idleTimeout: Duration,
  connectionSettings: ClientConnectionSettings) {

  require(maxConnections > 0, "max-connections must be > 0")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(maxRedirects >= 0, "max-redirects must be >= 0")
  require(idleTimeout >= Duration.Zero, "idleTimeout must be > 0 or 'infinite'")
}

object HostConnectorSettings extends SettingsCompanion[HostConnectorSettings]("akka.http") {
  def fromSubConfig(c: Config) = apply(
    c getInt "host-connector.max-connections",
    c getInt "host-connector.max-retries",
    c getInt "host-connector.max-redirects",
    c getBoolean "host-connector.pipelining",
    c getPotentiallyInfiniteDuration "host-connector.idle-timeout",
    ClientConnectionSettings fromSubConfig c.getConfig("client"))
}
