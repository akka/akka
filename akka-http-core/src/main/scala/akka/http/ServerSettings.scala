/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import com.typesafe.config.Config

import scala.language.implicitConversions
import scala.collection.immutable
import scala.concurrent.duration._

import akka.ConfigurationException
import akka.actor.{ ActorSystem, ActorRefFactory }
import akka.io.Inet.SocketOption

import akka.http.impl.util._

import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.{ Host, Server }

final case class ServerSettings(
  serverHeader: Option[Server],
  timeouts: ServerSettings.Timeouts,
  maxConnections: Int,
  remoteAddressHeader: Boolean,
  rawRequestUriHeader: Boolean,
  transparentHeadRequests: Boolean,
  verboseErrorMessages: Boolean,
  responseHeaderSizeHint: Int,
  backlog: Int,
  socketOptions: immutable.Traversable[SocketOption],
  defaultHostHeader: Host,
  parserSettings: ParserSettings) {

  require(0 < maxConnections, "max-connections must be > 0")
  require(0 < responseHeaderSizeHint, "response-size-hint must be > 0")
  require(0 < backlog, "backlog must be > 0")
}

object ServerSettings extends SettingsCompanion[ServerSettings]("akka.http.server") {
  final case class Timeouts(idleTimeout: Duration,
                            bindTimeout: FiniteDuration) {
    require(bindTimeout > Duration.Zero, "bindTimeout must be > 0")
  }
  implicit def timeoutsShortcut(s: ServerSettings): Timeouts = s.timeouts

  def fromSubConfig(root: Config, c: Config) = apply(
    c.getString("server-header").toOption.map(Server(_)),
    Timeouts(
      c getPotentiallyInfiniteDuration "idle-timeout",
      c getFiniteDuration "bind-timeout"),
    c getInt "max-connections",
    c getBoolean "remote-address-header",
    c getBoolean "raw-request-uri-header",
    c getBoolean "transparent-head-requests",
    c getBoolean "verbose-error-messages",
    c getIntBytes "response-header-size-hint",
    c getInt "backlog",
    SocketOptionSettings.fromSubConfig(root, c.getConfig("socket-options")),
    defaultHostHeader =
      HttpHeader.parse("Host", c getString "default-host-header") match {
        case HttpHeader.ParsingResult.Ok(x: Host, Nil) ⇒ x
        case result ⇒
          val info = result.errors.head.withSummary("Configured `default-host-header` is illegal")
          throw new ConfigurationException(info.formatPretty)
      },
    ParserSettings.fromSubConfig(root, c.getConfig("parsing")))

  def apply(optionalSettings: Option[ServerSettings])(implicit actorRefFactory: ActorRefFactory): ServerSettings =
    optionalSettings getOrElse apply(actorSystem)

  /**
   * Creates an instance of ServerSettings using the configuration provided by the given
   * ActorSystem.
   *
   * Java API
   */
  def create(system: ActorSystem): ServerSettings = ServerSettings(system)

  /**
   * Creates an instance of ServerSettings using the given Config.
   *
   * Java API
   */
  def create(config: Config): ServerSettings = ServerSettings(config)

  /**
   * Create an instance of ServerSettings using the given String of config overrides to override
   * settings set in the class loader of this class (i.e. by application.conf or reference.conf files in
   * the class loader of this class).
   *
   * Java API
   */
  def create(configOverrides: String): ServerSettings = ServerSettings(configOverrides)
}

