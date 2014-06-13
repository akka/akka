/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import language.implicitConversions
import com.typesafe.config.Config
import scala.concurrent.duration._
import akka.actor.ActorRefFactory
import akka.http.parsing.ParserSettings
import akka.http.model.parser.HeaderParser
import akka.http.model.headers.{ Server, Host, RawHeader }
import akka.http.util._
import akka.ConfigurationException

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
      HeaderParser.parseHeader(RawHeader("Host", c getString "default-host-header")) match {
        case Right(x: Host) ⇒ x
        case Left(error)    ⇒ throw new ConfigurationException(error.withSummary("Configured `default-host-header` is illegal").formatPretty)
        case Right(_)       ⇒ throw new IllegalStateException
      },
    ParserSettings fromSubConfig c.getConfig("parsing"))

  def apply(optionalSettings: Option[ServerSettings])(implicit actorRefFactory: ActorRefFactory): ServerSettings =
    optionalSettings getOrElse apply(actorSystem)
}

