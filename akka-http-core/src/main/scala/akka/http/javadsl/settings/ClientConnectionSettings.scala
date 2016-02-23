/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.settings

import java.util.{ Optional, Random }

import akka.http.impl.settings.ClientConnectionSettingsImpl
import akka.http.javadsl.model.headers.UserAgent
import akka.io.Inet.SocketOption
import com.typesafe.config.Config

import akka.http.impl.util.JavaMapping.Implicits._
import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Public API but not intended for subclassing
 */
abstract class ClientConnectionSettings private[akka] () { self: ClientConnectionSettingsImpl ⇒
  def getUserAgentHeader: Optional[UserAgent]
  def getConnectingTimeout: FiniteDuration
  def getIdleTimeout: Duration
  def getRequestHeaderSizeHint: Int
  def getWebsocketRandomFactory: java.util.function.Supplier[Random]
  def getSocketOptions: java.lang.Iterable[SocketOption]
  def getParserSettings: ParserSettings

  // ---

  def withUserAgentHeader(newValue: Optional[UserAgent]): ClientConnectionSettings = self.copy(userAgentHeader = newValue.asScala.map(_.asScala))
  def withConnectingTimeout(newValue: FiniteDuration): ClientConnectionSettings = self.copy(connectingTimeout = newValue)
  def withIdleTimeout(newValue: Duration): ClientConnectionSettings = self.copy(idleTimeout = newValue)
  def withRequestHeaderSizeHint(newValue: Int): ClientConnectionSettings = self.copy(requestHeaderSizeHint = newValue)
  def withWebsocketRandomFactory(newValue: java.util.function.Supplier[Random]): ClientConnectionSettings = self.copy(websocketRandomFactory = () ⇒ newValue.get())
  def withSocketOptions(newValue: java.lang.Iterable[SocketOption]): ClientConnectionSettings = self.copy(socketOptions = newValue.asScala.toList)
  def withParserSettings(newValue: ParserSettings): ClientConnectionSettings = self.copy(parserSettings = newValue.asScala)

}

object ClientConnectionSettings extends SettingsCompanion[ClientConnectionSettings] {
  def create(config: Config): ClientConnectionSettings = ClientConnectionSettingsImpl(config)
  def create(configOverrides: String): ClientConnectionSettings = ClientConnectionSettingsImpl(configOverrides)
}
