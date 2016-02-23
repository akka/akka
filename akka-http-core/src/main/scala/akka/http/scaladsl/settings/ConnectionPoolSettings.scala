/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.settings

import akka.http.impl.settings.ConnectionPoolSettingsImpl
import akka.http.javadsl.{ settings ⇒ js }
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/**
 * Public API but not intended for subclassing
 */
abstract class ConnectionPoolSettings extends js.ConnectionPoolSettings { self: ConnectionPoolSettingsImpl ⇒
  def maxConnections: Int
  def maxRetries: Int
  def maxOpenRequests: Int
  def pipeliningLimit: Int
  def idleTimeout: Duration
  def connectionSettings: ClientConnectionSettings

  /* JAVA APIs */

  final override def getConnectionSettings: js.ClientConnectionSettings = connectionSettings
  final override def getPipeliningLimit: Int = pipeliningLimit
  final override def getIdleTimeout: Duration = idleTimeout
  final override def getMaxConnections: Int = maxConnections
  final override def getMaxOpenRequests: Int = maxOpenRequests
  final override def getMaxRetries: Int = maxRetries

  // ---

  // overrides for more precise return type
  override def withMaxConnections(n: Int): ConnectionPoolSettings = self.copy(maxConnections = n)
  override def withMaxRetries(n: Int): ConnectionPoolSettings = self.copy(maxRetries = n)
  override def withMaxOpenRequests(newValue: Int): ConnectionPoolSettings = self.copy(maxOpenRequests = newValue)
  override def withPipeliningLimit(newValue: Int): ConnectionPoolSettings = self.copy(pipeliningLimit = newValue)
  override def withIdleTimeout(newValue: Duration): ConnectionPoolSettings = self.copy(idleTimeout = newValue)

  // overloads for idiomatic Scala use
  def withConnectionSettings(newValue: ClientConnectionSettings): ConnectionPoolSettings = self.copy(connectionSettings = newValue)
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings] {
  override def apply(config: Config) = ConnectionPoolSettingsImpl(config)
  override def apply(configOverrides: String) = ConnectionPoolSettingsImpl(configOverrides)
}
