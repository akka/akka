/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.client

import akka.event.LoggingAdapter
import akka.http.model.HttpHeader
import akka.io.Inet
import com.typesafe.config.Config
import scala.collection.immutable
import scala.concurrent.duration.{ FiniteDuration, Duration }
import akka.actor.ActorRefFactory
import akka.http.util._

final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

final case class ConnectionPoolSetup(
  encrypted: Boolean,
  options: immutable.Traversable[Inet.SocketOption],
  settings: HostConnectionPoolSettings,
  defaultHeaders: List[HttpHeader],
  log: LoggingAdapter)

final case class HostConnectionPoolSettings(
  maxConnections: Int,
  maxRetries: Int,
  pipeliningLimit: Int,
  idleTimeout: Duration,
  shutdownGracePeriod: FiniteDuration,
  connectionSettings: ClientConnectionSettings) {

  require(maxConnections > 0, "max-connections must be > 0")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(pipeliningLimit > 0, "pipelining-limit must be > 0")
  require(idleTimeout >= Duration.Zero, "idleTimeout must be >= 0")
  require(shutdownGracePeriod > Duration.Zero, "shutdownGracePeriod must be > 0")
}

object HostConnectionPoolSettings extends SettingsCompanion[HostConnectionPoolSettings]("akka.http.host-connection-pool") {
  def fromSubConfig(c: Config) = {
    apply(
      c getInt "max-connections",
      c getInt "max-retries",
      c getInt "pipelining-limit",
      c getPotentiallyInfiniteDuration "idle-timeout",
      c getFiniteDuration "shutdown-grace-period",
      ClientConnectionSettings fromSubConfig c.getConfig("client"))
  }

  def apply(optionalSettings: Option[HostConnectionPoolSettings])(implicit actorRefFactory: ActorRefFactory): HostConnectionPoolSettings =
    optionalSettings getOrElse apply(actorSystem)
}