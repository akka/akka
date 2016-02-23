/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.settings

import java.util.{ Optional, Random }

import akka.http.impl.settings.ServerSettingsImpl
import akka.http.javadsl.model.headers.Host
import akka.http.javadsl.model.headers.Server
import akka.io.Inet.SocketOption
import akka.http.impl.util.JavaMapping.Implicits._
import com.typesafe.config.Config
import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Public API but not intended for subclassing
 */
abstract class ServerSettings { self: ServerSettingsImpl ⇒
  def getServerHeader: Optional[Server]
  def getTimeouts: ServerSettings.Timeouts
  def getMaxConnections: Int
  def getPipeliningLimit: Int
  def getRemoteAddressHeader: Boolean
  def getRawRequestUriHeader: Boolean
  def getTransparentHeadRequests: Boolean
  def getVerboseErrorMessages: Boolean
  def getResponseHeaderSizeHint: Int
  def getBacklog: Int
  def getSocketOptions: java.lang.Iterable[SocketOption]
  def getDefaultHostHeader: Host
  def getWebsocketRandomFactory: java.util.function.Supplier[Random]
  def getParserSettings: ParserSettings

  // ---

  def withServerHeader(newValue: Optional[Server]): ServerSettings = self.copy(serverHeader = newValue.asScala.map(_.asScala))
  def withTimeouts(newValue: ServerSettings.Timeouts): ServerSettings = self.copy(timeouts = newValue.asScala)
  def withMaxConnections(newValue: Int): ServerSettings = self.copy(maxConnections = newValue)
  def withPipeliningLimit(newValue: Int): ServerSettings = self.copy(pipeliningLimit = newValue)
  def withRemoteAddressHeader(newValue: Boolean): ServerSettings = self.copy(remoteAddressHeader = newValue)
  def withRawRequestUriHeader(newValue: Boolean): ServerSettings = self.copy(rawRequestUriHeader = newValue)
  def withTransparentHeadRequests(newValue: Boolean): ServerSettings = self.copy(transparentHeadRequests = newValue)
  def withVerboseErrorMessages(newValue: Boolean): ServerSettings = self.copy(verboseErrorMessages = newValue)
  def withResponseHeaderSizeHint(newValue: Int): ServerSettings = self.copy(responseHeaderSizeHint = newValue)
  def withBacklog(newValue: Int): ServerSettings = self.copy(backlog = newValue)
  def withSocketOptions(newValue: java.lang.Iterable[SocketOption]): ServerSettings = self.copy(socketOptions = newValue.asScala.toList)
  def withDefaultHostHeader(newValue: Host): ServerSettings = self.copy(defaultHostHeader = newValue.asScala)
  def withParserSettings(newValue: ParserSettings): ServerSettings = self.copy(parserSettings = newValue.asScala)
  def withWebsocketRandomFactory(newValue: java.util.function.Supplier[Random]): ServerSettings = self.copy(websocketRandomFactory = () ⇒ newValue.get())

}

object ServerSettings extends SettingsCompanion[ServerSettings] {
  trait Timeouts {
    def idleTimeout: Duration
    def requestTimeout: Duration
    def bindTimeout: FiniteDuration

    // ---
    def withIdleTimeout(newValue: Duration): ServerSettings.Timeouts = self.copy(idleTimeout = newValue)
    def withRequestTimeout(newValue: Duration): ServerSettings.Timeouts = self.copy(requestTimeout = newValue)
    def withBindTimeout(newValue: FiniteDuration): ServerSettings.Timeouts = self.copy(bindTimeout = newValue)

    /** INTERNAL API */
    protected def self = this.asInstanceOf[ServerSettingsImpl.Timeouts]
  }

  override def create(config: Config): ServerSettings = ServerSettingsImpl(config)
  override def create(configOverrides: String): ServerSettings = ServerSettingsImpl(configOverrides)
}