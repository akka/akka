/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.http.impl.settings.ConnectionPoolSettingsImpl
import akka.http.javadsl.{ settings ⇒ js }
import akka.http.scaladsl.ClientTransport
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

@ApiMayChange
sealed trait PoolImplementation extends js.PoolImplementation
@ApiMayChange
object PoolImplementation {
  case object Legacy extends PoolImplementation
  case object New extends PoolImplementation
}

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ConnectionPoolSettings extends js.ConnectionPoolSettings { self: ConnectionPoolSettingsImpl ⇒
  def maxConnections: Int
  def minConnections: Int
  def maxRetries: Int
  def maxOpenRequests: Int
  def pipeliningLimit: Int
  def idleTimeout: Duration
  def connectionSettings: ClientConnectionSettings

  /**
   * The underlying transport used to connect to hosts. By default [[ClientTransport.TCP]] is used.
   */
  @deprecated("Deprecated in favor of connectionSettings.transport", "10.1.0")
  def transport: ClientTransport = connectionSettings.transport

  @ApiMayChange
  def poolImplementation: PoolImplementation

  /** The time after which the pool will drop an entity automatically if it wasn't read or discarded */
  @ApiMayChange
  def responseEntitySubscriptionTimeout: Duration

  // ---

  // overrides for more precise return type
  override def withMaxConnections(n: Int): ConnectionPoolSettings = self.copy(maxConnections = n)
  override def withMinConnections(n: Int): ConnectionPoolSettings = self.copy(minConnections = n)
  override def withMaxRetries(n: Int): ConnectionPoolSettings = self.copy(maxRetries = n)
  override def withMaxOpenRequests(newValue: Int): ConnectionPoolSettings = self.copy(maxOpenRequests = newValue)
  override def withPipeliningLimit(newValue: Int): ConnectionPoolSettings = self.copy(pipeliningLimit = newValue)
  override def withIdleTimeout(newValue: Duration): ConnectionPoolSettings = self.copy(idleTimeout = newValue)

  // overloads for idiomatic Scala use
  def withConnectionSettings(newValue: ClientConnectionSettings): ConnectionPoolSettings = self.copy(connectionSettings = newValue)

  @ApiMayChange
  def withPoolImplementation(newValue: PoolImplementation): ConnectionPoolSettings = self.copy(poolImplementation = newValue)

  @ApiMayChange
  override def withResponseEntitySubscriptionTimeout(newValue: Duration): ConnectionPoolSettings = self.copy(responseEntitySubscriptionTimeout = newValue)

  /**
   * Since 10.1.0, the transport is configured in [[ClientConnectionSettings]]. This method is a shortcut for
   * `withUpdatedConnectionSettings(_.withTransport(newTransport))`.
   */
  def withTransport(newTransport: ClientTransport): ConnectionPoolSettings =
    withUpdatedConnectionSettings(_.withTransport(newTransport))

  def withUpdatedConnectionSettings(f: ClientConnectionSettings ⇒ ClientConnectionSettings): ConnectionPoolSettings
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings] {
  override def apply(config: Config) = ConnectionPoolSettingsImpl(config)
  override def apply(configOverrides: String) = ConnectionPoolSettingsImpl(configOverrides)
}
