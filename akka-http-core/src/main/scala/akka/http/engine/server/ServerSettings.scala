/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import language.implicitConversions
import com.typesafe.config.Config
import scala.concurrent.duration._
import akka.actor.ActorRefFactory
import akka.ConfigurationException
import akka.http.engine.parsing.ParserSettings
import akka.http.model.HttpHeader
import akka.http.model.headers.{ Server, Host }
import akka.http.util._

final case class ServerSettings(
  serverHeader: Option[Server],
  timeouts: ServerSettings.Timeouts,
  remoteAddressHeader: Boolean,
  rawRequestUriHeader: Boolean,
  transparentHeadRequests: Boolean,
  verboseErrorMessages: Boolean,
  responseHeaderSizeHint: Int,
  defaultHostHeader: Host,
  parserSettings: ParserSettings) {

  require(0 <= responseHeaderSizeHint, "response-size-hint must be > 0")
}

object ServerSettings extends SettingsCompanion[ServerSettings]("akka.http.server") {
  final case class Timeouts(idleTimeout: Duration,
                            bindTimeout: FiniteDuration) {
    require(bindTimeout >= Duration.Zero, "bindTimeout must be > 0")
  }
  implicit def timeoutsShortcut(s: ServerSettings): Timeouts = s.timeouts

  def fromSubConfig(c: Config) = apply(
    c.getString("server-header").toOption.map(Server(_)),
    Timeouts(
      c getPotentiallyInfiniteDuration "idle-timeout",
      c getFiniteDuration "bind-timeout"),
    c getBoolean "remote-address-header",
    c getBoolean "raw-request-uri-header",
    c getBoolean "transparent-head-requests",
    c getBoolean "verbose-error-messages",
    c getIntBytes "response-header-size-hint",
    defaultHostHeader =
      HttpHeader.parse("Host", c getString "default-host-header") match {
        case HttpHeader.ParsingResult.Ok(x: Host, Nil) ⇒ x
        case result ⇒
          val info = result.errors.head.withSummary("Configured `default-host-header` is illegal")
          throw new ConfigurationException(info.formatPretty)
      },
    ParserSettings fromSubConfig c.getConfig("parsing"))
}

