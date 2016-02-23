/**
 * Copyright (C) 2009-2014 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import java.util.Random

import akka.http.impl.engine.ws.Randoms
import akka.http.impl.util._
import akka.http.scaladsl.model.headers.`User-Agent`
import akka.http.scaladsl.settings.ParserSettings
import akka.io.Inet.SocketOption
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }

/** INTERNAL API */
private[akka] final case class ClientConnectionSettingsImpl(
  userAgentHeader: Option[`User-Agent`],
  connectingTimeout: FiniteDuration,
  idleTimeout: Duration,
  requestHeaderSizeHint: Int,
  websocketRandomFactory: () ⇒ Random,
  socketOptions: immutable.Seq[SocketOption],
  parserSettings: ParserSettings)
  extends akka.http.scaladsl.settings.ClientConnectionSettings {

  require(connectingTimeout >= Duration.Zero, "connectingTimeout must be >= 0")
  require(requestHeaderSizeHint > 0, "request-size-hint must be > 0")

  override def productPrefix = "ClientConnectionSettings"
}

object ClientConnectionSettingsImpl extends SettingsCompanion[ClientConnectionSettingsImpl]("akka.http.client") {
  def fromSubConfig(root: Config, inner: Config) = {
    val c = inner.withFallback(root.getConfig(prefix))
    new ClientConnectionSettingsImpl(
      userAgentHeader = c.getString("user-agent-header").toOption.map(`User-Agent`(_)),
      connectingTimeout = c getFiniteDuration "connecting-timeout",
      idleTimeout = c getPotentiallyInfiniteDuration "idle-timeout",
      requestHeaderSizeHint = c getIntBytes "request-header-size-hint",
      websocketRandomFactory = Randoms.SecureRandomInstances, // can currently only be overridden from code
      socketOptions = SocketOptionSettings.fromSubConfig(root, c.getConfig("socket-options")),
      parserSettings = ParserSettingsImpl.fromSubConfig(root, c.getConfig("parsing")))
  }

}
