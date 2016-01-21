/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.impl.util._
import akka.http.javadsl.ConnectionContext
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

final case class HostConnectionPoolSetup(host: String, port: Int, setup: ConnectionPoolSetup)

final case class ConnectionPoolSetup(
  settings: ConnectionPoolSettings,
  connectionContext: ConnectionContext = ConnectionContext.noEncryption(),
  log: LoggingAdapter)

object ConnectionPoolSetup {
  /** Java API */
  def create(settings: ConnectionPoolSettings,
             connectionContext: ConnectionContext,
             log: LoggingAdapter): ConnectionPoolSetup =
    ConnectionPoolSetup(settings, connectionContext, log)
}

final class ConnectionPoolSettings(
  val maxConnections: Int,
  val maxRetries: Int,
  val maxOpenRequests: Int,
  val pipeliningLimit: Int,
  val idleTimeout: Duration,
  val connectionSettings: ClientConnectionSettings) {

  require(maxConnections > 0, "max-connections must be > 0")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(maxOpenRequests > 0 && (maxOpenRequests & (maxOpenRequests - 1)) == 0, "max-open-requests must be a power of 2 > 0")
  require(pipeliningLimit > 0, "pipelining-limit must be > 0")
  require(idleTimeout >= Duration.Zero, "idle-timeout must be >= 0")

  def copy(
    maxConnections: Int = maxConnections,
    maxRetries: Int = maxRetries,
    maxOpenRequests: Int = maxOpenRequests,
    pipeliningLimit: Int = pipeliningLimit,
    idleTimeout: Duration = idleTimeout,
    connectionSettings: ClientConnectionSettings = connectionSettings) =
    new ConnectionPoolSettings(
      maxConnections = maxConnections,
      maxRetries = maxRetries,
      maxOpenRequests = maxOpenRequests,
      pipeliningLimit = pipeliningLimit,
      idleTimeout = idleTimeout,
      connectionSettings = connectionSettings)

  // TODO we should automate generating those
  override def toString = {
    getClass.getSimpleName + "(" +
      maxConnections + "," +
      maxRetries + "," +
      maxOpenRequests + "," +
      pipeliningLimit + "," +
      idleTimeout + "," +
      connectionSettings +
      ")"
  }
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings]("akka.http.host-connection-pool") {
  def fromSubConfig(root: Config, c: Config) = {
    new ConnectionPoolSettings(
      c getInt "max-connections",
      c getInt "max-retries",
      c getInt "max-open-requests",
      c getInt "pipelining-limit",
      c getPotentiallyInfiniteDuration "idle-timeout",
      ClientConnectionSettings.fromSubConfig(root, c.getConfig("client")))
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

  def apply(
    maxConnections: Int,
    maxRetries: Int,
    maxOpenRequests: Int,
    pipeliningLimit: Int,
    idleTimeout: Duration,
    connectionSettings: ClientConnectionSettings) =
    new ConnectionPoolSettings(
      maxConnections: Int,
      maxRetries: Int,
      maxOpenRequests: Int,
      pipeliningLimit: Int,
      idleTimeout: Duration,
      connectionSettings: ClientConnectionSettings)
}