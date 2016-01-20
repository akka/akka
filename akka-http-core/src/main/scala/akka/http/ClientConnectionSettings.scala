/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.util.Random

import akka.http.impl.engine.ws.Randoms
import akka.io.Inet.SocketOption

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.collection.immutable

import com.typesafe.config.Config
import akka.actor.ActorSystem

import akka.http.impl.util._

import akka.http.scaladsl.model.headers.`User-Agent`

final class ClientConnectionSettings(
  val userAgentHeader: Option[`User-Agent`],
  val connectingTimeout: FiniteDuration,
  val idleTimeout: Duration,
  val requestHeaderSizeHint: Int,
  val websocketRandomFactory: () ⇒ Random,
  val socketOptions: immutable.Traversable[SocketOption],
  val parserSettings: ParserSettings) {

  require(connectingTimeout >= Duration.Zero, "connectingTimeout must be >= 0")
  require(requestHeaderSizeHint > 0, "request-size-hint must be > 0")
}

object ClientConnectionSettings extends SettingsCompanion[ClientConnectionSettings]("akka.http.client") {
  def fromSubConfig(root: Config, inner: Config) = {
    val c = inner.withFallback(root.getConfig(prefix))
    new ClientConnectionSettings(
      userAgentHeader = c.getString("user-agent-header").toOption.map(`User-Agent`(_)),
      connectingTimeout = c getFiniteDuration "connecting-timeout",
      idleTimeout = c getPotentiallyInfiniteDuration "idle-timeout",
      requestHeaderSizeHint = c getIntBytes "request-header-size-hint",
      websocketRandomFactory = Randoms.SecureRandomInstances, // can currently only be overridden from code
      socketOptions = SocketOptionSettings.fromSubConfig(root, c.getConfig("socket-options")),
      parserSettings = ParserSettings.fromSubConfig(root, c.getConfig("parsing")))
  }

  /**
   * Creates an instance of ClientConnectionSettings using the configuration provided by the given
   * ActorSystem.
   *
   * Java API
   */
  def create(system: ActorSystem): ClientConnectionSettings = ClientConnectionSettings(system)

  /**
   * Creates an instance of ClientConnectionSettings using the given Config.
   *
   * Java API
   */
  def create(config: Config): ClientConnectionSettings = ClientConnectionSettings(config)

  /**
   * Create an instance of ClientConnectionSettings using the given String of config overrides to override
   * settings set in the class loader of this class (i.e. by application.conf or reference.conf files in
   * the class loader of this class).
   *
   * Java API
   */
  def create(configOverrides: String): ClientConnectionSettings = ClientConnectionSettings(configOverrides)
}