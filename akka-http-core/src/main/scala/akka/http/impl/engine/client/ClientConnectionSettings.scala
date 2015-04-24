/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import com.typesafe.config.Config
import scala.concurrent.duration.{ FiniteDuration, Duration }
import akka.http.scaladsl.model.headers.`User-Agent`
import akka.http.impl.engine.parsing.ParserSettings
import akka.http.impl.util._

final case class ClientConnectionSettings(
  userAgentHeader: Option[`User-Agent`],
  connectingTimeout: FiniteDuration,
  idleTimeout: Duration,
  requestHeaderSizeHint: Int,
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
      ParserSettings fromSubConfig c.getConfig("parsing"))
  }
}