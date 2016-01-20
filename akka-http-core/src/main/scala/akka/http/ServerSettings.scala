/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.util.Random

import akka.http.impl.engine.ws.Randoms
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

final class ServerSettings(
  val serverHeader: Option[Server],
  val timeouts: ServerSettings.Timeouts,
  val maxConnections: Int,
  val pipeliningLimit: Int,
  val remoteAddressHeader: Boolean,
  val rawRequestUriHeader: Boolean,
  val transparentHeadRequests: Boolean,
  val verboseErrorMessages: Boolean,
  val responseHeaderSizeHint: Int,
  val backlog: Int,
  val socketOptions: immutable.Traversable[SocketOption],
  val defaultHostHeader: Host,
  val websocketRandomFactory: () ⇒ Random,
  val parserSettings: ParserSettings) {

  require(0 < maxConnections, "max-connections must be > 0")
  require(0 < pipeliningLimit && pipeliningLimit <= 1024, "pipelining-limit must be > 0 and <= 1024")
  require(0 < responseHeaderSizeHint, "response-size-hint must be > 0")
  require(0 < backlog, "backlog must be > 0")
}

object ServerSettings extends SettingsCompanion[ServerSettings]("akka.http.server") {
  final class Timeouts(
    val idleTimeout: Duration,
    val requestTimeout: Duration,
    val bindTimeout: FiniteDuration) {
    require(idleTimeout > Duration.Zero, "idleTimeout must be infinite or > 0")
    require(requestTimeout > Duration.Zero, "requestTimeout must be infinite or > 0")
    require(bindTimeout > Duration.Zero, "bindTimeout must be > 0")
  }
  implicit def timeoutsShortcut(s: ServerSettings): Timeouts = s.timeouts

  def fromSubConfig(root: Config, c: Config) = new ServerSettings(
    c.getString("server-header").toOption.map(Server(_)),
    new Timeouts(
      c getPotentiallyInfiniteDuration "idle-timeout",
      c getPotentiallyInfiniteDuration "request-timeout",
      c getFiniteDuration "bind-timeout"),
    c getInt "max-connections",
    c getInt "pipelining-limit",
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
    Randoms.SecureRandomInstances, // can currently only be overridden from code
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

