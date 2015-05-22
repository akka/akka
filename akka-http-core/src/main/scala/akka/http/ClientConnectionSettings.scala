/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.io.Inet.SocketOption

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.collection.immutable

import com.typesafe.config.Config
import akka.actor.ActorSystem

import akka.http.impl.util._

import akka.http.scaladsl.model.headers.`User-Agent`

final case class ClientConnectionSettings(
  userAgentHeader: Option[`User-Agent`],
  connectingTimeout: FiniteDuration,
  idleTimeout: Duration,
  requestHeaderSizeHint: Int,
  socketOptions: immutable.Traversable[SocketOption],
  parserSettings: ParserSettings) {

  require(connectingTimeout >= Duration.Zero, "connectingTimeout must be >= 0")
  require(requestHeaderSizeHint > 0, "request-size-hint must be > 0")
}

object ClientConnectionSettings extends SettingsCompanion[ClientConnectionSettings]("akka.http.client") {
  def fromSubConfig(c: Config) = {
    apply(
      c.getString("user-agent-header").toOption.map(`User-Agent`(_)),
      c getFiniteDuration "connecting-timeout",
      c getPotentiallyInfiniteDuration "idle-timeout",
      c getIntBytes "request-header-size-hint",
      SocketOptionSettings fromSubConfig c.getConfig("socket-options"),
      ParserSettings fromSubConfig c.getConfig("parsing"))
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