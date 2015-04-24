/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import akka.event.LoggingAdapter
import akka.io.Inet
import com.typesafe.config.Config
import scala.collection.immutable
import scala.concurrent.duration.Duration
import akka.http.impl.util._

final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

final case class ConnectionPoolSetup(
  encrypted: Boolean,
  options: immutable.Traversable[Inet.SocketOption],
  settings: ConnectionPoolSettings,
  log: LoggingAdapter)

final case class ConnectionPoolSettings(
  maxConnections: Int,
  maxRetries: Int,
  maxOpenRequests: Int,
  pipeliningLimit: Int,
  idleTimeout: Duration,
  connectionSettings: ClientConnectionSettings) {

  require(maxConnections > 0, "max-connections must be > 0")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(maxOpenRequests > 0 && (maxOpenRequests & (maxOpenRequests - 1)) == 0, "max-open-requests must be a power of 2 > 0")
  require(pipeliningLimit > 0, "pipelining-limit must be > 0")
  require(idleTimeout >= Duration.Zero, "idleTimeout must be >= 0")
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings]("akka.http.host-connection-pool") {
  def fromSubConfig(c: Config) = {
    apply(
      c getInt "max-connections",
      c getInt "max-retries",
      c getInt "max-open-requests",
      c getInt "pipelining-limit",
      c getPotentiallyInfiniteDuration "idle-timeout",
      ClientConnectionSettings fromSubConfig c.getConfig("client"))
  }
}