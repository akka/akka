/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.settings

import java.net.InetSocketAddress
import java.util.function.Supplier
import java.util.{ Optional, Random }

import akka.actor.ActorSystem
import akka.annotation.DoNotInherit
import akka.http.impl.settings.ClientConnectionSettingsImpl
import akka.http.javadsl.model.headers.UserAgent
import akka.io.Inet.SocketOption
import com.typesafe.config.Config
import akka.http.impl.util.JavaMapping.Implicits._

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters
import scala.compat.java8.OptionConverters._
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ClientConnectionSettings private[akka] () { self: ClientConnectionSettingsImpl ⇒
  /* JAVA APIs */

  final def getConnectingTimeout: FiniteDuration = connectingTimeout
  final def getParserSettings: ParserSettings = parserSettings
  final def getIdleTimeout: Duration = idleTimeout
  final def getSocketOptions: java.lang.Iterable[SocketOption] = socketOptions.asJava
  final def getUserAgentHeader: Optional[UserAgent] = OptionConverters.toJava(userAgentHeader)
  final def getLogUnencryptedNetworkBytes: Optional[Int] = OptionConverters.toJava(logUnencryptedNetworkBytes)
  final def getRequestHeaderSizeHint: Int = requestHeaderSizeHint
  final val getWebsocketRandomFactory: Supplier[Random] = new Supplier[Random] {
    override def get(): Random = websocketRandomFactory()
  }
  final def getLocalAddress: Optional[InetSocketAddress] = OptionConverters.toJava(localAddress)

  // ---

  def withUserAgentHeader(newValue: Optional[UserAgent]): ClientConnectionSettings = self.copy(userAgentHeader = newValue.asScala.map(_.asScala))
  def withConnectingTimeout(newValue: FiniteDuration): ClientConnectionSettings = self.copy(connectingTimeout = newValue)
  def withIdleTimeout(newValue: Duration): ClientConnectionSettings = self.copy(idleTimeout = newValue)
  def withRequestHeaderSizeHint(newValue: Int): ClientConnectionSettings = self.copy(requestHeaderSizeHint = newValue)
  def withLogUnencryptedNetworkBytes(newValue: Optional[Int]): ClientConnectionSettings = self.copy(logUnencryptedNetworkBytes = OptionConverters.toScala(newValue))
  def withWebsocketRandomFactory(newValue: java.util.function.Supplier[Random]): ClientConnectionSettings = self.copy(websocketRandomFactory = () ⇒ newValue.get())
  def withSocketOptions(newValue: java.lang.Iterable[SocketOption]): ClientConnectionSettings = self.copy(socketOptions = newValue.asScala.toList)
  def withParserSettings(newValue: ParserSettings): ClientConnectionSettings = self.copy(parserSettings = newValue.asScala)
  def withLocalAddress(newValue: Optional[InetSocketAddress]): ClientConnectionSettings = self.copy(localAddress = OptionConverters.toScala(newValue))
}

object ClientConnectionSettings extends SettingsCompanion[ClientConnectionSettings] {
  def create(config: Config): ClientConnectionSettings = ClientConnectionSettingsImpl(config)
  def create(configOverrides: String): ClientConnectionSettings = ClientConnectionSettingsImpl(configOverrides)
  override def create(system: ActorSystem): ClientConnectionSettings = create(system.settings.config)
}
