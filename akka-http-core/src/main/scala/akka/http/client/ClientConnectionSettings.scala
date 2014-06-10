/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import akka.actor.ActorRefFactory
import akka.http.model.headers.`User-Agent`
import akka.http.parsing.ParserSettings
import akka.http.util._

final case class ClientConnectionSettings(
  userAgentHeader: Option[`User-Agent`],
  connectingTimeout: Duration,
  idleTimeout: Duration,
  requestTimeout: Duration,
  reapingCycle: Duration,
  requestHeaderSizeHint: Int,
  maxEncryptionChunkSize: Int,
  proxySettings: Map[String, ProxySettings],
  parserSettings: ParserSettings) {

  require(connectingTimeout >= Duration.Zero, "connectingTimeout must be > 0 or 'infinite'")
  require(idleTimeout >= Duration.Zero, "idleTimeout must be > 0 or 'infinite'")
  require(requestTimeout >= Duration.Zero, "requestTimeout must be > 0 or 'infinite'")
  require(reapingCycle >= Duration.Zero, "reapingCycle must be > 0 or 'infinite'")
  require(requestHeaderSizeHint > 0, "request-size-hint must be > 0")
  require(maxEncryptionChunkSize > 0, "max-encryption-chunk-size must be > 0")
}

object ClientConnectionSettings extends SettingsCompanion[ClientConnectionSettings]("akka.http.client") {
  def fromSubConfig(c: Config) = {
    apply(
      c.getString("user-agent-header").toOption.map(`User-Agent`(_)),
      c getPotentiallyInfiniteDuration "connecting-timeout",
      c getPotentiallyInfiniteDuration "idle-timeout",
      c getPotentiallyInfiniteDuration "request-timeout",
      c getPotentiallyInfiniteDuration "reaping-cycle",
      c getIntBytes "request-header-size-hint",
      c getIntBytes "max-encryption-chunk-size",
      ProxySettings fromSubConfig c.getConfig("proxy"),
      ParserSettings fromSubConfig c.getConfig("parsing"))
  }

  def apply(optionalSettings: Option[ClientConnectionSettings])(implicit actorRefFactory: ActorRefFactory): ClientConnectionSettings =
    optionalSettings getOrElse apply(actorSystem)
}

