/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.lang.{ Iterable ⇒ JIterable }
import akka.http.scaladsl.HttpsContext
import com.typesafe.config.Config
import scala.collection.immutable
import scala.concurrent.duration.Duration
import akka.japi.Util._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.impl.util._
import akka.io.Inet

final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

final case class ConnectionPoolSetup(
  settings: ConnectionPoolSettings,
  httpsContext: Option[HttpsContext],
  log: LoggingAdapter)

object ConnectionPoolSetup {
  /** Java API */
  def create(settings: ConnectionPoolSettings,
             httpsContext: akka.japi.Option[akka.http.javadsl.HttpsContext],
             log: LoggingAdapter): ConnectionPoolSetup =
    ConnectionPoolSetup(settings, httpsContext.map(_.asInstanceOf[HttpsContext]), log)
}

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
  require(idleTimeout >= Duration.Zero, "idle-timeout must be >= 0")
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

  /**
   * Creates an instance of ConnectionPoolSettings using the configuration provided by the given
   * ActorSystem.
   *
   * Java API
   */
  def create(system: ActorSystem): ConnectionPoolSettings = ConnectionPoolSettings(system)

  /**
   * Creates an instance of ConnectionPoolSettings using the given Config.
   *
   * Java API
   */
  def create(config: Config): ConnectionPoolSettings = ConnectionPoolSettings(config)

  /**
   * Create an instance of ConnectionPoolSettings using the given String of config overrides to override
   * settings set in the class loader of this class (i.e. by application.conf or reference.conf files in
   * the class loader of this class).
   *
   * Java API
   */
  def create(configOverrides: String): ConnectionPoolSettings = ConnectionPoolSettings(configOverrides)
}