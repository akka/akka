/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.settings

import akka.http.impl.settings.ConnectionPoolSettingsImpl
import com.typesafe.config.Config

import scala.concurrent.duration.Duration
import akka.http.impl.util.JavaMapping.Implicits._

/**
 * Public API but not intended for subclassing
 */
abstract class ConnectionPoolSettings private[akka] () { self: ConnectionPoolSettingsImpl ⇒
  def getMaxConnections: Int
  def getMaxRetries: Int
  def getMaxOpenRequests: Int
  def getPipeliningLimit: Int
  def getIdleTimeout: Duration
  def getConnectionSettings: ClientConnectionSettings

  // ---

  def withMaxConnections(n: Int): ConnectionPoolSettings = self.copy(maxConnections = n)
  def withMaxRetries(n: Int): ConnectionPoolSettings = self.copy(maxRetries = n)
  def withMaxOpenRequests(newValue: Int): ConnectionPoolSettings = self.copy(maxOpenRequests = newValue)
  def withPipeliningLimit(newValue: Int): ConnectionPoolSettings = self.copy(pipeliningLimit = newValue)
  def withIdleTimeout(newValue: Duration): ConnectionPoolSettings = self.copy(idleTimeout = newValue)
  def withConnectionSettings(newValue: ClientConnectionSettings): ConnectionPoolSettings = self.copy(connectionSettings = newValue.asScala)
}

object ConnectionPoolSettings extends SettingsCompanion[ConnectionPoolSettings] {
  override def create(config: Config): ConnectionPoolSettings = ConnectionPoolSettingsImpl(config)
  override def create(configOverrides: String): ConnectionPoolSettings = ConnectionPoolSettingsImpl(configOverrides)
}