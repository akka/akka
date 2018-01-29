/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.{ SettingsCompanion, _ }
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings, PoolImplementation }
import com.typesafe.config.Config

import scala.concurrent.duration.Duration

/** INTERNAL API */
@InternalApi
private[akka] final case class ConnectionPoolSettingsImpl(
  maxConnections:                    Int,
  minConnections:                    Int,
  maxRetries:                        Int,
  maxOpenRequests:                   Int,
  pipeliningLimit:                   Int,
  idleTimeout:                       Duration,
  connectionSettings:                ClientConnectionSettings,
  poolImplementation:                PoolImplementation,
  responseEntitySubscriptionTimeout: Duration,
  transport:                         ClientTransport)
  extends ConnectionPoolSettings {

  def this(
    maxConnections:                    Int,
    minConnections:                    Int,
    maxRetries:                        Int,
    maxOpenRequests:                   Int,
    pipeliningLimit:                   Int,
    idleTimeout:                       Duration,
    connectionSettings:                ClientConnectionSettings,
    poolImplementation:                PoolImplementation,
    responseEntitySubscriptionTimeout: Duration) =
    this(maxConnections, minConnections, maxRetries, maxOpenRequests, pipeliningLimit, idleTimeout, connectionSettings,
      poolImplementation, responseEntitySubscriptionTimeout, ClientTransport.TCP)

  require(maxConnections > 0, "max-connections must be > 0")
  require(minConnections >= 0, "min-connections must be >= 0")
  require(minConnections <= maxConnections, "min-connections must be <= max-connections")
  require(maxRetries >= 0, "max-retries must be >= 0")
  require(maxOpenRequests > 0, "max-open-requests must be a power of 2 > 0.")
  require((maxOpenRequests & (maxOpenRequests - 1)) == 0, "max-open-requests must be a power of 2. " + suggestPowerOfTwo(maxOpenRequests))
  require(pipeliningLimit > 0, "pipelining-limit must be > 0")
  require(idleTimeout >= Duration.Zero, "idle-timeout must be >= 0")

  override def productPrefix = "ConnectionPoolSettings"

  private def suggestPowerOfTwo(around: Int): String = {
    val firstBit = 31 - Integer.numberOfLeadingZeros(around)

    val below = 1 << firstBit
    val above = 1 << (firstBit + 1)

    s"Perhaps try $below or $above."
  }
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
      ClientConnectionSettingsImpl.fromSubConfig(root, c.getConfig("client")),
      c.getString("pool-implementation").toLowerCase match {
        case "legacy" ⇒ PoolImplementation.Legacy
        case "new"    ⇒ PoolImplementation.New
      },
      c getPotentiallyInfiniteDuration "response-entity-subscription-timeout"
    )
  }
}
