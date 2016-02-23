/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.settings

import java.util.Random
import java.util.function.Supplier

import akka.http.impl.settings.ServerSettingsImpl
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.{ settings ⇒ js }
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.headers.Server
import akka.io.Inet.SocketOption
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.compat.java8.OptionConverters
import scala.concurrent.duration.{ FiniteDuration, Duration }
import scala.language.implicitConversions

/**
 * Public API but not intended for subclassing
 */
abstract class ServerSettings private[akka] () extends akka.http.javadsl.settings.ServerSettings { self: ServerSettingsImpl ⇒
  def serverHeader: Option[Server]
  def timeouts: ServerSettings.Timeouts
  def maxConnections: Int
  def pipeliningLimit: Int
  def remoteAddressHeader: Boolean
  def rawRequestUriHeader: Boolean
  def transparentHeadRequests: Boolean
  def verboseErrorMessages: Boolean
  def responseHeaderSizeHint: Int
  def backlog: Int
  def socketOptions: immutable.Seq[SocketOption]
  def defaultHostHeader: Host
  def websocketRandomFactory: () ⇒ Random
  def parserSettings: ParserSettings

  /* Java APIs */

  override def getBacklog = backlog
  override def getDefaultHostHeader = defaultHostHeader.asJava
  override def getPipeliningLimit = pipeliningLimit
  override def getParserSettings: js.ParserSettings = parserSettings
  override def getMaxConnections = maxConnections
  override def getTransparentHeadRequests = transparentHeadRequests
  override def getResponseHeaderSizeHint = responseHeaderSizeHint
  override def getVerboseErrorMessages = verboseErrorMessages
  override def getSocketOptions = socketOptions.asJava
  override def getServerHeader = OptionConverters.toJava(serverHeader.map(_.asJava))
  override def getTimeouts = timeouts
  override def getRawRequestUriHeader = rawRequestUriHeader
  override def getRemoteAddressHeader = remoteAddressHeader
  override def getWebsocketRandomFactory = new Supplier[Random] {
    override def get(): Random = websocketRandomFactory()
  }

  // ---

  // override for more specific return type
  override def withMaxConnections(newValue: Int): ServerSettings = self.copy(maxConnections = newValue)
  override def withPipeliningLimit(newValue: Int): ServerSettings = self.copy(pipeliningLimit = newValue)
  override def withRemoteAddressHeader(newValue: Boolean): ServerSettings = self.copy(remoteAddressHeader = newValue)
  override def withRawRequestUriHeader(newValue: Boolean): ServerSettings = self.copy(rawRequestUriHeader = newValue)
  override def withTransparentHeadRequests(newValue: Boolean): ServerSettings = self.copy(transparentHeadRequests = newValue)
  override def withVerboseErrorMessages(newValue: Boolean): ServerSettings = self.copy(verboseErrorMessages = newValue)
  override def withResponseHeaderSizeHint(newValue: Int): ServerSettings = self.copy(responseHeaderSizeHint = newValue)
  override def withBacklog(newValue: Int): ServerSettings = self.copy(backlog = newValue)
  override def withSocketOptions(newValue: java.lang.Iterable[SocketOption]): ServerSettings = self.copy(socketOptions = newValue.asScala.toList)
  override def withWebsocketRandomFactory(newValue: java.util.function.Supplier[Random]): ServerSettings = self.copy(websocketRandomFactory = () ⇒ newValue.get())

  // overloads for Scala idiomatic use
  def withTimeouts(newValue: ServerSettings.Timeouts): ServerSettings = self.copy(timeouts = newValue)
  def withServerHeader(newValue: Option[Server]): ServerSettings = self.copy(serverHeader = newValue)
  def withDefaultHostHeader(newValue: Host): ServerSettings = self.copy(defaultHostHeader = newValue)
  def withParserSettings(newValue: ParserSettings): ServerSettings = self.copy(parserSettings = newValue)
  def withWebsocketRandomFactory(newValue: () ⇒ Random): ServerSettings = self.copy(websocketRandomFactory = newValue)
  def withSocketOptions(newValue: immutable.Seq[SocketOption]): ServerSettings = self.copy(socketOptions = newValue)

}

object ServerSettings extends SettingsCompanion[ServerSettings] {
  trait Timeouts extends akka.http.javadsl.settings.ServerSettings.Timeouts {
    // ---
    // override for more specific return types
    override def withIdleTimeout(newValue: Duration): ServerSettings.Timeouts = self.copy(idleTimeout = newValue)
    override def withRequestTimeout(newValue: Duration): ServerSettings.Timeouts = self.copy(requestTimeout = newValue)
    override def withBindTimeout(newValue: FiniteDuration): ServerSettings.Timeouts = self.copy(bindTimeout = newValue)
  }

  implicit def timeoutsShortcut(s: ServerSettings): Timeouts = s.timeouts

  override def apply(config: Config): ServerSettings = ServerSettingsImpl(config)
  override def apply(configOverrides: String): ServerSettings = ServerSettingsImpl(configOverrides)
}