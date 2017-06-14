/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.settings

import java.net.InetSocketAddress
import java.util.Random

import akka.annotation.DoNotInherit
import akka.http.impl.util._
import akka.http.impl.settings.ClientConnectionSettingsImpl
import akka.http.scaladsl.model.headers.`User-Agent`
import akka.io.Inet.SocketOption
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ClientConnectionSettings private[akka] () extends akka.http.javadsl.settings.ClientConnectionSettings { self: ClientConnectionSettingsImpl ⇒
  def userAgentHeader: Option[`User-Agent`]
  def connectingTimeout: FiniteDuration
  def idleTimeout: Duration
  def requestHeaderSizeHint: Int
  def websocketRandomFactory: () ⇒ Random
  def socketOptions: immutable.Seq[SocketOption]
  def parserSettings: ParserSettings
  def logUnencryptedNetworkBytes: Option[Int]
  def localAddress: Option[InetSocketAddress]

  // ---

  // overrides for more specific return type
  override def withConnectingTimeout(newValue: FiniteDuration): ClientConnectionSettings = self.copy(connectingTimeout = newValue)
  override def withIdleTimeout(newValue: Duration): ClientConnectionSettings = self.copy(idleTimeout = newValue)
  override def withRequestHeaderSizeHint(newValue: Int): ClientConnectionSettings = self.copy(requestHeaderSizeHint = newValue)

  // overloads for idiomatic Scala use
  def withWebsocketRandomFactory(newValue: () ⇒ Random): ClientConnectionSettings = self.copy(websocketRandomFactory = newValue)
  def withUserAgentHeader(newValue: Option[`User-Agent`]): ClientConnectionSettings = self.copy(userAgentHeader = newValue)
  def withLogUnencryptedNetworkBytes(newValue: Option[Int]): ClientConnectionSettings = self.copy(logUnencryptedNetworkBytes = newValue)
  def withSocketOptions(newValue: immutable.Seq[SocketOption]): ClientConnectionSettings = self.copy(socketOptions = newValue)
  def withParserSettings(newValue: ParserSettings): ClientConnectionSettings = self.copy(parserSettings = newValue)
  def withLocalAddress(newValue: Option[InetSocketAddress]): ClientConnectionSettings = self.copy(localAddress = newValue)

  /**
   * Returns a new instance with the given local address set if the given override is `Some(address)`, otherwise
   * return this instance unchanged.
   */
  def withLocalAddressOverride(overrideLocalAddressOption: Option[InetSocketAddress]): ClientConnectionSettings =
    if (overrideLocalAddressOption.isDefined) withLocalAddress(overrideLocalAddressOption)
    else this
}

object ClientConnectionSettings extends SettingsCompanion[ClientConnectionSettings] {
  override def apply(config: Config): ClientConnectionSettings = ClientConnectionSettingsImpl(config)
  override def apply(configOverrides: String): ClientConnectionSettings = ClientConnectionSettingsImpl(configOverrides)

  object LogUnencryptedNetworkBytes {
    def apply(string: String): Option[Int] =
      string.toRootLowerCase match {
        case "off" ⇒ None
        case value ⇒ Option(value.toInt)
      }
  }
}
