/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import language.implicitConversions
import com.typesafe.config.Config
import scala.concurrent.duration._
import akka.http.parsing.ParserSettings
import akka.http.model.parser.HeaderParser
import akka.http.model.headers.{ Server, Host, RawHeader }
import akka.http.util._

case class ServerSettings(
  serverHeader: Option[Server],
  sslEncryption: Boolean,
  pipeliningLimit: Int,
  timeouts: ServerSettings.Timeouts,
  timeoutHandler: String,
  reapingCycle: Duration,
  remoteAddressHeader: Boolean,
  rawRequestUriHeader: Boolean,
  transparentHeadRequests: Boolean,
  chunklessStreaming: Boolean,
  verboseErrorMessages: Boolean,
  responseHeaderSizeHint: Int,
  maxEncryptionChunkSize: Int,
  defaultHostHeader: Host,
  parserSettings: ParserSettings) {

  require(reapingCycle >= Duration.Zero, "reapingCycle must be > 0 or 'infinite'")
  require(0 <= pipeliningLimit && pipeliningLimit <= 128, "pipelining-limit must be >= 0 and <= 128")
  require(0 <= responseHeaderSizeHint, "response-size-hint must be > 0")
  require(0 < maxEncryptionChunkSize, "max-encryption-chunk-size must be > 0")
}

object ServerSettings extends SettingsCompanion[ServerSettings]("akka.http.server") {
  case class Timeouts(idleTimeout: Duration,
                      requestTimeout: Duration,
                      timeoutTimeout: Duration,
                      bindTimeout: Duration,
                      unbindTimeout: Duration,
                      parseErrorAbortTimeout: Duration) {
    require(idleTimeout >= Duration.Zero, "idleTimeout must be > 0 or 'infinite'")
    require(requestTimeout >= Duration.Zero, "requestTimeout must be > 0 or 'infinite'")
    require(timeoutTimeout >= Duration.Zero, "timeoutTimeout must be > 0 or 'infinite'")
    require(bindTimeout >= Duration.Zero, "bindTimeout must be > 0 or 'infinite'")
    require(unbindTimeout >= Duration.Zero, "unbindTimeout must be > 0 or 'infinite'")
    require(!requestTimeout.isFinite || idleTimeout > requestTimeout,
      "idle-timeout must be > request-timeout (if the latter is not 'infinite')")
    require(!idleTimeout.isFinite || idleTimeout > 1.second, // the current implementation is not fit for
      "an idle-timeout < 1 second is not supported") // very short idle-timeout settings
  }
  implicit def timeoutsShortcut(s: ServerSettings): Timeouts = s.timeouts

  def fromSubConfig(c: Config) = apply(
    c.getString("server-header").toOption.map(Server(_)),
    c getBoolean "ssl-encryption",
    c.getString("pipelining-limit") match { case "disabled" ⇒ 0; case _ ⇒ c getInt "pipelining-limit" },
    Timeouts(
      c getDuration "idle-timeout",
      c getDuration "request-timeout",
      c getDuration "timeout-timeout",
      c getDuration "bind-timeout",
      c getDuration "unbind-timeout",
      c getDuration "parse-error-abort-timeout"),
    c getString "timeout-handler",
    c getDuration "reaping-cycle",
    c getBoolean "remote-address-header",
    c getBoolean "raw-request-uri-header",
    c getBoolean "transparent-head-requests",
    c getBoolean "chunkless-streaming",
    c getBoolean "verbose-error-messages",
    c getIntBytes "response-header-size-hint",
    c getIntBytes "max-encryption-chunk-size",
    defaultHostHeader =
      HeaderParser.parseHeader(RawHeader("Host", c getString "default-host-header")) match {
        case Right(x: Host) ⇒ x
        case Left(error)    ⇒ sys.error(error.withSummary("Configured `default-host-header` is illegal").formatPretty)
        case Right(_)       ⇒ throw new IllegalStateException
      },
    ParserSettings fromSubConfig c.getConfig("parsing"))
}

